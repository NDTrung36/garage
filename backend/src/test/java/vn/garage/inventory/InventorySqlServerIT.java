package vn.garage.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import vn.garage.inventory.application.StockCommands;
import vn.garage.inventory.domain.Part;
import vn.garage.inventory.domain.PartRepository;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.infrastructure.JdbcPartRepository;
import vn.garage.inventory.infrastructure.JdbcStockMovementRepository;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

import static org.junit.jupiter.api.Assertions.*;

class InventorySqlServerIT {
    private static String url;
    private static Properties credentials;

    @BeforeAll
    static void migrateDedicatedDatabase() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to the dedicated SQL Server migration test database");
        credentials = new Properties();
        String username = System.getenv("MIGRATION_TEST_USERNAME");
        String password = System.getenv("MIGRATION_TEST_PASSWORD");
        if (username != null && !username.isBlank()) credentials.setProperty("user", username);
        if (password != null && !password.isBlank()) credentials.setProperty("password", password);
        try (Connection c = connect()) {
            assertTrue(scalar(c, "SELECT DB_NAME()").matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to run inventory tests outside the dedicated migration database");
        }
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @Test
    void catalogCreatesDefaultStockAndUsesOptimisticVersion() throws Exception {
        String code = code();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                JdbcPartRepository repository = repository(c);
                var created = repository.create(new Part(code.toLowerCase(), "  Dầu nhớt tổng hợp  ", "Lít", "BOTH", 0));
                assertEquals(code, created.code());
                assertEquals("Dầu nhớt tổng hợp", created.name());
                assertEquals("0.000", created.stockQuantity());
                assertEquals(0, created.version());
                assertThrows(PartRepository.DuplicateCode.class,
                        () -> repository.create(new Part(code, "Trùng mã", "Lít", "CAR", 5)));
                var updated = repository.update(Long.parseLong(created.id()), "Dầu nhớt", null, null,
                        null, null, created.version()).orElseThrow();
                assertEquals(1, updated.version());
                assertThrows(PartRepository.StaleVersion.class,
                        () -> repository.update(Long.parseLong(created.id()), "Tên cũ", null, null,
                                null, null, created.version()));
                assertEquals("Dầu nhớt", scalar(c, "SELECT name FROM dbo.parts WHERE code=?", code));
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void unitCanChangeBeforeMovementAndIsLockedAfterReceipt() throws Exception {
        String code = code();
        long partId;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            partId = insertPart(c, code);
            var beforeReceipt = repository(c).update(partId, null, "Chai", null, null, null, 0).orElseThrow();
            long actor = insertUser(c, code);
            insertReceipt(c, partId, actor);
            c.commit();

            try (Connection updater = connect()) {
                JdbcPartRepository repository = repository(updater);
                assertThrows(PartRepository.StaleVersion.class,
                        () -> repository.update(partId, null, "Lít", null, null, null, beforeReceipt.version()));
                assertThrows(PartRepository.UnitLocked.class,
                        () -> repository.update(partId, null, "Lít", null, null, null, beforeReceipt.version() + 1));
                assertEquals("Chai", scalar(updater, "SELECT unit FROM dbo.parts WHERE id=?", partId));
            }
        } finally {
            cleanup(code);
        }
    }

    @Test
    @Timeout(20)
    void partUpdateSerializesWithFirstReceiptAndReceiptUsesCommittedUnit() throws Exception {
        String code = code();
        long partId;
        long actorId;
        try (Connection setup = connect()) {
            setup.setAutoCommit(false);
            partId = insertPart(setup, code);
            actorId = insertUser(setup, code);
            setup.commit();
        }

        try (Connection updater = connect(); Connection receiver = connect(); var pool = Executors.newSingleThreadExecutor()) {
            updater.setAutoCommit(false);
            receiver.setAutoCommit(false);
            scalar(updater, "SELECT id FROM dbo.parts WITH (UPDLOCK,HOLDLOCK) WHERE id=?", partId);
            CountDownLatch started = new CountDownLatch(1);
            var receipt = pool.submit(() -> {
                started.countDown();
                receiver.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                scalar(receiver, "SELECT id FROM dbo.parts WITH (UPDLOCK,HOLDLOCK) WHERE id=?", partId);
                execute(receiver, "UPDATE dbo.parts SET stock_quantity=stock_quantity+1,version=version+1 WHERE id=?", partId);
                insertReceiptWithQuantity(receiver, partId, actorId);
                receiver.commit();
                return scalar(receiver, "SELECT unit FROM dbo.parts WHERE id=?", partId);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(receipt.isDone(), "The first receipt must wait while the unit edit owns the part lock");

            JdbcPartRepository repository = repository(updater);
            var updated = repository.update(partId, null, "Hộp", null, null, null, 0).orElseThrow();
            assertEquals("Hộp", updated.unit());
            updater.commit();
            assertEquals("Hộp", receipt.get(8, TimeUnit.SECONDS));
            assertEquals("Hộp:1.000:2", scalar(updater, "SELECT CONCAT(unit,':',stock_quantity,':',version) FROM dbo.parts WHERE id=?", partId));
        } finally {
            cleanup(code);
        }
    }

    @Test
    void receiptsAdjustmentsAndHistoryKeepExactBalancesAndReplayResults() throws Exception {
        String code = code();
        long partId;
        long actorId;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            partId = insertPart(c, code);
            actorId = insertUser(c, code);
            c.commit();
        }
        String receiptKey = key();
        String adjustDownKey = key();
        String adjustUpKey = key();
        var runtime = runtime(null);
        try {
            var receipt = runtime.commands().receipt(partId, "2.500", "  Nhập hàng  ", "  Lô A  ",
                    receiptKey, actorId, "ADMIN");
            var down = runtime.commands().adjust(partId, "-0.750", "Kiểm kê giảm", adjustDownKey,
                    actorId, "ADMIN");
            var up = runtime.commands().adjust(partId, "+0.250", "Kiểm kê tăng", adjustUpKey,
                    actorId, "ADMIN");

            assertEquals(201, receipt.httpStatus());
            assertEquals("0.000", receipt.body().movement().quantityBefore());
            assertEquals("2.500", receipt.body().movement().quantityAfter());
            assertEquals("2.500", down.body().movement().quantityBefore());
            assertEquals("-0.750", down.body().movement().quantityDelta());
            assertEquals("1.750", down.body().movement().quantityAfter());
            assertEquals("1.750", up.body().movement().quantityBefore());
            assertEquals("0.250", up.body().movement().quantityDelta());
            assertEquals("2.000", up.body().stockQuantity());
            assertEquals("2.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", partId));
            assertEquals("3", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", partId));

            assertThrows(StockCommands.StockLimitExceeded.class, () -> runtime.commands().adjust(partId,
                    "-2.001", "Giảm quá tồn", key(), actorId, "ADMIN"));
            assertEquals("2.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", partId));
            assertEquals("3", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", partId));

            var replay = runtime.commands().receipt(partId, "2.500", "Nhập hàng", "Lô A",
                    receiptKey, actorId, "ADMIN");
            assertTrue(replay.replayed());
            assertEquals(receipt.body(), replay.body(), "Replay returns the saved response from its original commit");
            assertEquals("3", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", partId));

            var firstPage = runtime.history().list(0, 2, partId, null);
            assertEquals(3, firstPage.totalElements());
            assertEquals(List.of(up.body().movement().id(), down.body().movement().id()),
                    firstPage.items().stream().map(StockHistory.MovementView::id).toList());
            assertEquals("2.000", firstPage.items().get(0).quantityAfter());
            assertEquals("1.750", firstPage.items().get(1).quantityAfter());
            var secondPage = runtime.history().list(1, 2, partId, null);
            assertEquals(1, secondPage.items().size());
            assertEquals("0.000", secondPage.items().getFirst().quantityBefore());
            var receipts = runtime.history().list(0, 2, partId, StockMovement.Type.RECEIPT);
            assertEquals(1, receipts.totalElements());
            assertEquals(receipt.body().movement().id(), receipts.items().getFirst().id());
            assertEquals("3", scalar("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope IN (?,?) AND request_key IN (?,?,?)",
                    IdempotencyExecutor.scope("inventory.stock.receipt", actorId),
                    IdempotencyExecutor.scope("inventory.stock.adjustment", actorId),
                    receiptKey, adjustDownKey, adjustUpKey));
        } finally {
            cleanup(code);
        }
    }

    @Test
    void repositoryFailureAfterBalanceUpdateRollsBackLedgerBalanceAndIdempotency() throws Exception {
        String code = code();
        long partId;
        long actorId;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            partId = insertPart(c, code);
            actorId = insertUser(c, code);
            c.commit();
        }
        String requestKey = key();
        var source = dataSource();
        JdbcStockMovementRepository delegate = new JdbcStockMovementRepository(new JdbcTemplate(source));
        StockMovementRepository failing = new DelegatingStockMovementRepository(delegate) {
            @Override public StockMovement insert(NewMovement movement) {
                throw new DataAccessResourceFailureException("forced failure after stock balance update");
            }
        };
        var runtime = runtime(source, failing);
        try {
            assertThrows(DataAccessResourceFailureException.class, () -> runtime.commands().receipt(partId,
                    "2.500", "Lỗi sau cập nhật", null, requestKey, actorId, "ADMIN"));
            assertEquals("0.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", partId));
            assertEquals("0", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", partId));
            assertEquals("0", scalar("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    IdempotencyExecutor.scope("inventory.stock.receipt", actorId), requestKey));
        } finally {
            cleanup(code);
        }
    }

    @Test
    @Timeout(20)
    void competingAdjustmentsSerializeOnPartAndRejectTheSecondReduction() throws Exception {
        String code = code();
        long partId;
        long actorId;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            partId = insertPart(c, code);
            actorId = insertUser(c, code);
            c.commit();
        }
        String seedKey = key();
        var basic = runtime(null);
        basic.commands().receipt(partId, "1.000", "Tồn cạnh tranh", null, seedKey, actorId, "ADMIN");
        var lockedPart = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstLock = new AtomicBoolean(true);
        var source = dataSource();
        JdbcStockMovementRepository delegate = new JdbcStockMovementRepository(new JdbcTemplate(source));
        StockMovementRepository blocking = new DelegatingStockMovementRepository(delegate) {
            @Override public List<ItemNet> netForRepairItems(List<Long> ids) { return delegate.netForRepairItems(ids); }
        @Override public java.util.Optional<LockedPart> lockPart(long id) {
                var part = super.lockPart(id);
                if (id == partId && firstLock.compareAndSet(true, false)) {
                    lockedPart.countDown();
                    await(releaseFirst);
                }
                return part;
            }
        };
        var runtime = runtime(source, blocking);
        String firstKey = key();
        String secondKey = key();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runtime.commands().adjust(partId, "-0.750", "Giảm tồn", firstKey, actorId, "ADMIN"));
            assertTrue(lockedPart.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> runtime.commands().adjust(partId, "-0.750", "Giảm tồn", secondKey, actorId, "ADMIN"));
            Thread.sleep(250);
            assertFalse(second.isDone(), "Concurrent adjustment waits for the part row lock");
            releaseFirst.countDown();
            assertEquals("0.250", first.get(8, TimeUnit.SECONDS).body().stockQuantity());
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> second.get(8, TimeUnit.SECONDS));
            assertInstanceOf(StockCommands.StockLimitExceeded.class, failed.getCause());
            assertEquals("0.250", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", partId));
            assertEquals("2", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", partId));
        } finally {
            releaseFirst.countDown();
            cleanup(code);
        }
    }

    private Runtime runtime(StockMovementRepository repository) {
        return runtime(dataSource(), repository);
    }

    private Runtime runtime(DriverManagerDataSource source, StockMovementRepository repository) {
        var db = new JdbcTemplate(source);
        var stock = repository == null ? new JdbcStockMovementRepository(db) : repository;
        var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
        var idempotency = new IdempotencyExecutor(new JdbcIdempotencyStore(db), writes,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        return new Runtime(new StockCommands(stock, idempotency), new StockHistory(stock));
    }

    private DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setConnectionProperties(credentials);
        return source;
    }

    private record Runtime(StockCommands commands, StockHistory history) { }

    private static class DelegatingStockMovementRepository implements StockMovementRepository {
        private final StockMovementRepository delegate;
        DelegatingStockMovementRepository(StockMovementRepository delegate) { this.delegate = delegate; }
        @Override public List<ItemNet> netForRepairItems(List<Long> ids) { return delegate.netForRepairItems(ids); }
        @Override public java.util.Optional<LockedPart> lockPart(long id) { return delegate.lockPart(id); }
        @Override public void updateStock(long id, StockQuantity quantity) { delegate.updateStock(id, quantity); }
        @Override public StockMovement insert(NewMovement movement) { return delegate.insert(movement); }
        @Override public MovementPage list(int page, int size, Long partId, StockMovement.Type type) {
            return delegate.list(page, size, partId, type);
        }
        @Override public java.util.Optional<StockMovement> findIssue(long issueId) { return delegate.findIssue(issueId); }
        @Override public java.util.Optional<StockMovement> lockIssue(long issueId) { return delegate.lockIssue(issueId); }
        @Override public StockQuantity returnedQuantity(long issueId) { return delegate.returnedQuantity(issueId); }
        @Override public List<IssueReference> issuesForRepairItems(java.util.Collection<Long> repairItemIds) {
            return delegate.issuesForRepairItems(repairItemIds);
        }
    }

    private static JdbcPartRepository repository(Connection connection) {
        return new JdbcPartRepository(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
    }

    private static long insertPart(Connection c, String code) throws SQLException {
        return Long.parseLong(scalar(c, """
                INSERT dbo.parts(code,name,unit,vehicle_type,reference_price)
                OUTPUT INSERTED.id VALUES(?,N'Phụ tùng test','Lít','BOTH',0)
                """, code));
    }

    private static long insertUser(Connection c, String username) throws SQLException {
        return Long.parseLong(scalar(c, """
                INSERT dbo.users(username,password_hash,display_name,role)
                OUTPUT INSERTED.id VALUES(?, 'test-hash', N'Nhân viên test', 'ADMIN')
                """, username));
    }

    private static void insertReceipt(Connection c, long part, long actor) throws SQLException {
        execute(c, "UPDATE dbo.parts SET stock_quantity=stock_quantity+1,version=version+1 WHERE id=?", part);
        execute(c, """
                INSERT dbo.stock_movements(part_id,movement_type,quantity_delta,quantity_before,
                    quantity_after,reason,created_by)
                VALUES(?,'RECEIPT',1,0,1,N'Nhập test',?)
                """, part, actor);
    }

    private static void insertReceiptWithQuantity(Connection c, long part, long actor) throws SQLException {
        execute(c, """
                INSERT dbo.stock_movements(part_id,movement_type,quantity_delta,quantity_before,
                    quantity_after,reason,created_by)
                VALUES(?,'RECEIPT',1,0,1,N'Nhập test',?)
                """, part, actor);
    }

    private static String code() {
        return "IT-PART-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static String key() {
        return "IT-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url, credentials);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; "
                    + "SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF; "
                    + "SET XACT_ABORT OFF; SET LOCK_TIMEOUT 5000;");
        }
        return connection;
    }

    private static String scalar(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "Expected one row for query: " + sql);
                return rows.getString(1);
            }
        }
    }

    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection c = connect()) {
            return scalar(c, sql, parameters);
        }
    }

    private static void execute(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private static void cleanup(String code) throws SQLException {
        try (Connection c = connect()) {
            String actorId = nullableScalar(c, "SELECT id FROM dbo.users WHERE username=?", code);
            if (actorId != null) {
                execute(c, "DELETE FROM dbo.idempotency_records WHERE scope IN (?,?)",
                        IdempotencyExecutor.scope("inventory.stock.receipt", Long.parseLong(actorId)),
                        IdempotencyExecutor.scope("inventory.stock.adjustment", Long.parseLong(actorId)));
            }
            execute(c, "DELETE FROM dbo.stock_movements WHERE part_id IN (SELECT id FROM dbo.parts WHERE code=?)", code);
            execute(c, "DELETE FROM dbo.parts WHERE code=?", code);
            execute(c, "DELETE FROM dbo.users WHERE username=?", code);
        }
    }

    private static String nullableScalar(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
