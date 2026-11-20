package vn.garage.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.inventory.infrastructure.JdbcStockMovementRepository;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.application.RepairPartQuery;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.repair.infrastructure.JdbcRepairPartRepository;
import vn.garage.repair.infrastructure.JdbcRepairOrderStore;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.audit.JdbcAuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

import static org.junit.jupiter.api.Assertions.*;

class RepairStockSqlServerIT {
    private static String url;
    private static Properties credentials;
    private static HikariDataSource writePool;

    @BeforeAll
    static void migrateDedicatedDatabase() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to the dedicated SQL Server migration test database");
        credentials = new Properties();
        String username = System.getenv("MIGRATION_TEST_USERNAME");
        String password = System.getenv("MIGRATION_TEST_PASSWORD");
        if (username != null && !username.isBlank()) credentials.setProperty("user", username);
        if (password != null && !password.isBlank()) credentials.setProperty("password", password);
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DB_NAME()")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to run repair stock tests outside the dedicated migration database");
        }
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        if (credentials.getProperty("user") != null) pool.setUsername(credentials.getProperty("user"));
        if (credentials.getProperty("password") != null) pool.setPassword(credentials.getProperty("password"));
        pool.setMaximumPoolSize(8);
        pool.setConnectionInitSql("SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; "
                + "SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF; "
                + "SET XACT_ABORT OFF; SET LOCK_TIMEOUT 5000");
        pool.setPoolName("repair-stock-it");
        writePool = new HikariDataSource(pool);
    }

    @AfterAll
    static void closeWritePool() { if (writePool != null) writePool.close(); }

    @Test
    void issueReturnAndReissueKeepSnapshotAndEnforceTheOriginalIssue() throws Exception {
        Fixture fixture = createFixture();
        Runtime runtime = runtime(null);
        try {
            String firstKey = key();
            var firstResult = runtime.commands.issue(fixture.orderId(), fixture.partId(), "2.500", firstKey,
                    fixture.actorId(), "STAFF");
            var first = firstResult.body();
            var replay = runtime.commands.issue(fixture.orderId(), fixture.partId(), "2.500", firstKey,
                    fixture.actorId(), "STAFF");
            assertTrue(replay.replayed());
            assertEquals(first, replay.body());
            assertEquals("-2.500", first.movement().quantityDelta());
            assertEquals("5.000", first.movement().quantityBefore());
            assertEquals("2.500", first.stockQuantity());
            assertEquals("2.500", first.partItem().quantity());
            assertEquals(253, first.partItem().lineTotal()); // 2.500 × 101, rounded half up.
            assertEquals(253, first.repairTotal());

            execute("UPDATE dbo.parts SET reference_price=999 WHERE id=?", fixture.partId());
            var second = runtime.commands.issue(fixture.orderId(), fixture.partId(), "0.500", key(),
                    fixture.actorId(), "STAFF").body();
            assertEquals(101, second.partItem().unitPrice(), "Later issues must retain the first issue's price snapshot");
            assertEquals("3.000", second.partItem().quantity());

            execute("UPDATE dbo.parts SET active=0 WHERE id=?", fixture.partId());
            String returnKey = key();
            var returnedResult = runtime.commands.returnPart(fixture.orderId(), Long.parseLong(first.movement().id()),
                    "1.000", returnKey, fixture.actorId(), "STAFF");
            var returned = returnedResult.body();
            var returnReplay = runtime.commands.returnPart(fixture.orderId(), Long.parseLong(first.movement().id()),
                    "1.000", returnKey, fixture.actorId(), "STAFF");
            assertTrue(returnReplay.replayed());
            assertEquals(returned, returnReplay.body());
            assertEquals("RETURN", returned.movement().movementType());
            assertEquals(first.movement().id(), returned.movement().originalIssueId());
            assertEquals("3.000", returned.stockQuantity());
            assertEquals("2.000", returned.partItem().quantity());
            assertThrows(RepairStockOperations.ReturnExceedsIssue.class,
                    () -> runtime.commands.returnPart(fixture.orderId(), Long.parseLong(first.movement().id()),
                            "1.501", key(), fixture.actorId(), "STAFF"));
            var remainder = runtime.commands.returnPart(fixture.orderId(), Long.parseLong(first.movement().id()),
                    "1.500", key(), fixture.actorId(), "STAFF").body();
            assertEquals("4.500", remainder.stockQuantity());
            assertEquals("0.500", remainder.partItem().quantity());

            var lastReturn = runtime.commands.returnPart(fixture.orderId(), Long.parseLong(second.movement().id()), "0.500",
                    key(), fixture.actorId(), "STAFF").body();
            assertEquals("5.000", lastReturn.stockQuantity());
            assertEquals("0.000", lastReturn.partItem().quantity());
            var zero = runtime.query().detail(fixture.orderId());
            assertEquals("0.000", zero.partItems().getFirst().quantity());
            assertEquals("0.000", zero.returnableIssues().get(0).remainingQuantity());
            assertEquals("-2.500", scalar("SELECT quantity_delta FROM dbo.stock_movements WHERE id=?", first.movement().id()),
                    "Returns must retain the original ISSUE row");

            execute("UPDATE dbo.parts SET active=1 WHERE id=?", fixture.partId());
            var reissued = runtime.commands.issue(fixture.orderId(), fixture.partId(), "0.250", key(),
                    fixture.actorId(), "STAFF").body();
            assertEquals(first.partItem().id(), reissued.partItem().id());
            assertEquals(101, reissued.partItem().unitPrice());
            assertEquals("0.250", reissued.partItem().quantity());
            assertEquals("-0.250", scalar("SELECT quantity_delta FROM dbo.stock_movements WHERE id=?", reissued.movement().id()));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void validatesPartOrderAndCompletionBeforeChangingStock() throws Exception {
        Fixture fixture = createFixture();
        Runtime runtime = runtime(null);
        try {
            long inactive = insertPart("IT-PART-" + fixture.suffix() + "-OFF", "CAR", false, "5.000");
            long wrongType = insertPart("IT-PART-" + fixture.suffix() + "-MOTO", "MOTORBIKE", true, "5.000");
            assertThrows(RepairStockOperations.PartVehicleMismatch.class, () -> runtime.commands.issue(
                    fixture.orderId(), wrongType, "1.000", key(), fixture.actorId(), "STAFF"));
            assertThrows(RepairStockOperations.PartInactive.class, () -> runtime.commands.issue(
                    fixture.orderId(), inactive, "1.000", key(), fixture.actorId(), "STAFF"));
            assertThrows(RepairStockOperations.InsufficientStock.class, () -> runtime.commands.issue(
                    fixture.orderId(), fixture.partId(), "5.001", key(), fixture.actorId(), "STAFF"));
            assertThrows(RepairStockOperations.PartNotFound.class, () -> runtime.commands.issue(
                    fixture.orderId(), Long.MAX_VALUE, "1.000", key(), fixture.actorId(), "STAFF"));
            assertThrows(RepairPartCommands.RepairOrderNotFound.class, () -> runtime.commands.issue(
                    Long.MAX_VALUE, fixture.partId(), "1.000", key(), fixture.actorId(), "STAFF"));

            var issue = runtime.commands.issue(fixture.orderId(), fixture.partId(), "1.000", key(),
                    fixture.actorId(), "STAFF").body();
            assertThrows(RepairStockOperations.IssueNotFound.class, () -> runtime.commands.returnPart(
                    fixture.otherOrderId(), Long.parseLong(issue.movement().id()), "0.500", key(),
                    fixture.actorId(), "STAFF"));
            long laborId = insertLabor(fixture.orderId());
            assertThrows(RepairPartCommands.PartItemNotFound.class, () -> runtime.commands.updatePrice(
                    fixture.orderId(), laborId, 100, "Sai loại dòng", currentVersion(fixture.orderId()),
                    fixture.actorId(), "STAFF"));
            long receiptId = insertReceiptMovement(fixture.partId(), fixture.actorId());
            assertThrows(RepairStockOperations.IssueNotFound.class, () -> runtime.commands.returnPart(
                    fixture.orderId(), receiptId, "0.500", key(), fixture.actorId(), "STAFF"));

            execute("UPDATE dbo.repair_orders SET status='COMPLETED',completed_by=?,completed_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00') WHERE id=?",
                    fixture.actorId(), fixture.orderId());
            assertThrows(RepairPartCommands.RepairNotInProgress.class, () -> runtime.commands.issue(
                    fixture.orderId(), fixture.partId(), "1.000", key(), fixture.actorId(), "STAFF"));
            assertEquals("4.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", fixture.partId()));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void priceChangeAuditsAndAuditFailureRollsBackPriceAndVersion() throws Exception {
        Fixture fixture = createFixture();
        Runtime runtime = runtime(null);
        try {
            var issued = runtime.commands.issue(fixture.orderId(), fixture.partId(), "0.500", key(),
                    fixture.actorId(), "STAFF").body();
            long expectedVersion = issued.orderVersion();
            var changed = runtime.commands.updatePrice(fixture.orderId(), Long.parseLong(issued.partItem().id()),
                    202, "Giá cập nhật", expectedVersion, fixture.actorId(), "STAFF");
            assertEquals(101, changed.repairTotal()); // 0.500 × 202 = 101.
            assertEquals("REPAIR_PART_PRICE_UPDATED:REPAIR_ITEM:Giá cập nhật:0.500:0.500:101:202:51:101:51:101:" + fixture.actorId(), scalar("""
                    SELECT CONCAT(action,':',entity_type,':',reason,':',JSON_VALUE(before_json,'$.quantity'),':',
                        JSON_VALUE(after_json,'$.quantity'),':',JSON_VALUE(before_json,'$.unitPrice'),':',
                        JSON_VALUE(after_json,'$.unitPrice'),':',JSON_VALUE(before_json,'$.lineTotal'),':',
                        JSON_VALUE(after_json,'$.lineTotal'),':',JSON_VALUE(before_json,'$.repairTotal'),':',
                        JSON_VALUE(after_json,'$.repairTotal'),':',actor_user_id)
                    FROM dbo.audit_logs WHERE entity_type='REPAIR_ITEM' AND entity_id=?
                    """, issued.partItem().id()));
            assertEquals("0.500", scalar("SELECT quantity FROM dbo.repair_items WHERE id=?", issued.partItem().id()));
            assertEquals("4.500", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", fixture.partId()));
            assertEquals("-0.500", scalar("SELECT quantity_delta FROM dbo.stock_movements WHERE id=?", issued.movement().id()));
            long changedVersion = changed.orderVersion();
            var samePrice = runtime.commands.updatePrice(fixture.orderId(), Long.parseLong(issued.partItem().id()),
                    202, "Không đổi", changedVersion, fixture.actorId(), "STAFF");
            assertEquals(changedVersion, samePrice.orderVersion());
            assertEquals("1", scalar("SELECT COUNT(*) FROM dbo.audit_logs WHERE entity_type='REPAIR_ITEM' AND entity_id=?",
                    issued.partItem().id()));
            assertThrows(RepairPartCommands.StaleVersion.class, () -> runtime.commands.updatePrice(
                    fixture.orderId(), Long.parseLong(issued.partItem().id()), 303, "Stale", expectedVersion,
                    fixture.actorId(), "STAFF"));
            assertThrows(IllegalArgumentException.class, () -> runtime.commands.updatePrice(
                    fixture.orderId(), Long.parseLong(issued.partItem().id()), 303, "  ", changedVersion,
                    fixture.actorId(), "STAFF"));

            RepairPartCommands failingAudit = runtime.commandsWithAudit((actor, action, type, entity, reason, before, after) -> {
                throw new IllegalStateException("forced audit failure");
            });
            long currentVersion = currentVersion(fixture.orderId());
            assertThrows(IllegalStateException.class, () -> failingAudit.updatePrice(fixture.orderId(),
                    Long.parseLong(issued.partItem().id()), 303, "Lỗi audit", currentVersion,
                    fixture.actorId(), "STAFF"));
            assertEquals("202", scalar("SELECT unit_price FROM dbo.repair_items WHERE id=?", issued.partItem().id()));
            assertEquals(Long.toString(currentVersion), scalar("SELECT version FROM dbo.repair_orders WHERE id=?", fixture.orderId()));
            assertEquals("1", scalar("SELECT COUNT(*) FROM dbo.audit_logs WHERE entity_type='REPAIR_ITEM' AND entity_id=?", issued.partItem().id()));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void totalLimitFailureRollsBackStockLedgerPartItemAndIdempotency() throws Exception {
        Fixture fixture = createFixture();
        Runtime runtime = runtime(null);
        try {
            insertLabor(fixture.orderId(), RepairTotals.MAX_SAFE_AMOUNT.longValueExact());
            String requestKey = key();
            assertThrows(RepairTotals.TotalLimitExceeded.class, () -> runtime.commands.issue(fixture.orderId(),
                    fixture.partId(), "0.500", requestKey, fixture.actorId(), "STAFF"));
            assertEquals("5.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", fixture.partId()));
            assertEquals("0", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=?", fixture.partId()));
            assertEquals("0", scalar("SELECT COUNT(*) FROM dbo.repair_items WHERE repair_order_id=? AND item_type='PART'", fixture.orderId()));
            assertEquals("0", scalar("SELECT version FROM dbo.repair_orders WHERE id=?", fixture.orderId()));
            assertEquals("0", scalar("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    IdempotencyExecutor.scope("repair.part.issue", fixture.actorId()), requestKey));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    @Timeout(20)
    void concurrentIssuesSerializeAndOnlyOneCanSpendTheAvailableStock() throws Exception {
        Fixture fixture = createFixture("7.000");
        Runtime runtime = runtime(null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { await(start); return runtime.commands.issue(fixture.orderId(), fixture.partId(),
                    "5.000", key(), fixture.actorId(), "STAFF"); });
            var b = pool.submit(() -> { await(start); return runtime.commands.issue(fixture.otherOrderId(), fixture.partId(),
                    "5.000", key(), fixture.actorId(), "STAFF"); });
            start.countDown();
            int succeeded = 0;
            int rejected = 0;
            for (var result : java.util.List.of(a, b)) {
                try { result.get(10, TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) {
                    assertInstanceOf(RepairStockOperations.InsufficientStock.class, failure.getCause());
                    rejected++;
                }
            }
            assertEquals(1, succeeded);
            assertEquals(1, rejected);
            assertEquals("2.000", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", fixture.partId()));
            assertEquals("1", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE part_id=? AND movement_type='ISSUE'",
                    fixture.partId()));
            assertEquals("1", scalar("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=?",
                    IdempotencyExecutor.scope("repair.part.issue", fixture.actorId())));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    @Timeout(20)
    void concurrentReturnsCannotExceedOneOriginalIssue() throws Exception {
        Fixture fixture = createFixture();
        Runtime runtime = runtime(null);
        var issued = runtime.commands.issue(fixture.orderId(), fixture.partId(), "1.000", key(),
                fixture.actorId(), "STAFF").body();
        long issueId = Long.parseLong(issued.movement().id());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { await(start); return runtime.commands.returnPart(fixture.orderId(),
                    issueId, "0.750", key(), fixture.actorId(), "STAFF"); });
            var b = pool.submit(() -> { await(start); return runtime.commands.returnPart(fixture.orderId(),
                    issueId, "0.750", key(), fixture.actorId(), "STAFF"); });
            start.countDown();
            int succeeded = 0;
            int rejected = 0;
            for (var result : java.util.List.of(a, b)) {
                try { result.get(10, TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) {
                    assertInstanceOf(RepairStockOperations.ReturnExceedsIssue.class, failure.getCause());
                    rejected++;
                }
            }
            assertEquals(1, succeeded);
            assertEquals(1, rejected);
            assertEquals("4.750", scalar("SELECT stock_quantity FROM dbo.parts WHERE id=?", fixture.partId()));
            assertEquals("0.250", scalar("SELECT quantity FROM dbo.repair_items WHERE id=?", issued.partItem().id()));
            assertEquals("1", scalar("SELECT COUNT(*) FROM dbo.stock_movements WHERE original_issue_id=? AND movement_type='RETURN'", issueId));
            assertEquals("0.250", scalar("SELECT ABS(quantity_delta)-COALESCE((SELECT SUM(quantity_delta) FROM dbo.stock_movements WHERE original_issue_id=? AND movement_type='RETURN'),0) FROM dbo.stock_movements WHERE id=?",
                    issueId, issueId));
        } finally {
            cleanup(fixture);
        }
    }

    private static Runtime runtime(AuditWriter writer) {
        HikariDataSource source = dataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        var transaction = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
        var idempotency = new IdempotencyExecutor(new JdbcIdempotencyStore(db), transaction,
                new ObjectMapper().findAndRegisterModules());
        var ledger = new JdbcStockMovementRepository(db);
        var repository = new JdbcRepairPartRepository(db);
        var orders = new JdbcRepairOrderStore(db);
        var stock = new RepairStockOperations(ledger);
        var commands = new RepairPartCommands(orders, repository, stock, idempotency,
                transaction, writer == null ? new JdbcAuditWriter(db, new ObjectMapper().findAndRegisterModules()) : writer);
        return new Runtime(commands, new RepairPartQuery(orders, repository, stock));
    }

    private static Fixture createFixture() throws SQLException { return createFixture("5.000"); }

    private static Fixture createFixture(String stock) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String phone = "+84" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000L));
        try (Connection c = connect()) {
            long actor = insert(c, "INSERT dbo.users(username,password_hash,display_name,role) OUTPUT INSERTED.id VALUES(?, 'it-hash', N'Nhân viên IT', 'STAFF')", "IT-REPAIR-" + suffix);
            long customer = insert(c, "INSERT dbo.customers(full_name,phone_normalized) OUTPUT INSERTED.id VALUES(N'Khách IT',?)", phone);
            String plate = "IT" + suffix;
            long vehicle = insert(c, "INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized) OUTPUT INSERTED.id VALUES(?,'CAR',?,?)", customer, plate, plate);
            long booking = insert(c, """
                    INSERT dbo.bookings(code,customer_id,vehicle_id,source,status,vehicle_type_snapshot,
                        contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                    OUTPUT INSERTED.id VALUES(?,?,?,'WALK_IN','CHECKED_IN','CAR',N'Khách IT',?,?,?,?)
                    """, "IT-BK-" + suffix, customer, vehicle, phone, plate, plate, actor);
            long otherBooking = insert(c, """
                    INSERT dbo.bookings(code,customer_id,vehicle_id,source,status,vehicle_type_snapshot,
                        contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                    OUTPUT INSERTED.id VALUES(?,?,?,'WALK_IN','CHECKED_IN','CAR',N'Khách IT',?,?,?,?)
                    """, "IT-BK2-" + suffix, customer, vehicle, phone, plate + "B", plate + "B", actor);
            long order = insert(c, "INSERT dbo.repair_orders(booking_id,received_by) OUTPUT INSERTED.id VALUES(?,?)", booking, actor);
            long otherOrder = insert(c, "INSERT dbo.repair_orders(booking_id,received_by) OUTPUT INSERTED.id VALUES(?,?)", otherBooking, actor);
            long part = insert(c, """
                    INSERT dbo.parts(code,name,unit,vehicle_type,reference_price,stock_quantity)
                    OUTPUT INSERTED.id VALUES(?,N'Dầu nhớt','Lít','CAR',101,?)
                    """, "IT-PART-" + suffix, new java.math.BigDecimal(stock));
            return new Fixture(suffix, actor, customer, vehicle, booking, otherBooking, order, otherOrder, part);
        }
    }

    private static long insertPart(String code, String vehicleType, boolean active, String stock) throws SQLException {
        try (Connection c = connect()) {
            return insert(c, """
                    INSERT dbo.parts(code,name,unit,vehicle_type,reference_price,stock_quantity,active)
                    OUTPUT INSERTED.id VALUES(?,N'Test part','Cái',?,101,?,?)
                    """, code, vehicleType, new java.math.BigDecimal(stock), active);
        }
    }

    private static long insertLabor(long orderId) throws SQLException {
        return insertLabor(orderId, 100);
    }

    private static long insertLabor(long orderId, long unitPrice) throws SQLException {
        try (Connection c = connect()) {
            return insert(c, """
                    INSERT dbo.repair_items(repair_order_id,item_type,description,unit_snapshot,quantity,unit_price)
                    OUTPUT INSERTED.id VALUES(?,'LABOR',N'Công sửa','Giờ',1,?)
                    """, orderId, unitPrice);
        }
    }

    private static long insertReceiptMovement(long partId, long actorId) throws SQLException {
        try (Connection c = connect(); PreparedStatement statement = c.prepareStatement("""
                INSERT dbo.stock_movements(part_id,movement_type,quantity_delta,quantity_before,quantity_after,reason,created_by)
                OUTPUT INSERTED.id VALUES(?,'RECEIPT',1,0,1,N'Phiếu nhập fixture',?)
                """)) {
            statement.setLong(1, partId);
            statement.setLong(2, actorId);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static long currentVersion(long orderId) throws SQLException {
        return Long.parseLong(scalar("SELECT version FROM dbo.repair_orders WHERE id=?", orderId));
    }

    private static long insert(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection c = connect(); PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = connect(); PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private static void cleanup(Fixture f) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                execute(c, "DELETE FROM dbo.idempotency_records WHERE scope IN (?,?)",
                        IdempotencyExecutor.scope("repair.part.issue", f.actorId()),
                        IdempotencyExecutor.scope("repair.part.return", f.actorId()));
                execute(c, "DELETE FROM dbo.audit_logs WHERE actor_user_id=?", f.actorId());
                execute(c, "DELETE FROM dbo.stock_movements WHERE movement_type='RETURN' AND repair_item_id IN (SELECT id FROM dbo.repair_items WHERE repair_order_id IN (?,?))", f.orderId(), f.otherOrderId());
                execute(c, "DELETE FROM dbo.stock_movements WHERE repair_item_id IN (SELECT id FROM dbo.repair_items WHERE repair_order_id IN (?,?))", f.orderId(), f.otherOrderId());
                execute(c, "DELETE FROM dbo.repair_items WHERE repair_order_id IN (?,?)", f.orderId(), f.otherOrderId());
                execute(c, "DELETE FROM dbo.repair_orders WHERE id IN (?,?)", f.orderId(), f.otherOrderId());
                execute(c, "DELETE FROM dbo.bookings WHERE id IN (?,?)", f.bookingId(), f.otherBookingId());
                execute(c, "DELETE FROM dbo.stock_movements WHERE part_id IN (SELECT id FROM dbo.parts WHERE code LIKE ?)",
                        "IT-PART-" + f.suffix() + "%");
                execute(c, "DELETE FROM dbo.parts WHERE code LIKE ?", "IT-PART-" + f.suffix() + "%");
                execute(c, "DELETE FROM dbo.vehicles WHERE id=?", f.vehicleId());
                execute(c, "DELETE FROM dbo.customers WHERE id=?", f.customerId());
                execute(c, "DELETE FROM dbo.users WHERE id=?", f.actorId());
                c.commit();
            } catch (SQLException failure) { c.rollback(); throw failure; }
        }
    }

    private static void execute(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private static String key() { return "IT-" + UUID.randomUUID().toString().replace("-", ""); }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, credentials);
        try (Statement statement = c.createStatement()) {
            statement.execute("SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; "
                    + "SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF; "
                    + "SET XACT_ABORT OFF; SET LOCK_TIMEOUT 5000;");
        }
        return c;
    }

    private static HikariDataSource dataSource() { return writePool; }

    private static void await(CountDownLatch start) {
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private record Fixture(String suffix, long actorId, long customerId, long vehicleId, long bookingId,
                           long otherBookingId, long orderId, long otherOrderId, long partId) { }
    private record Runtime(RepairPartCommands commands, RepairPartQuery query) {
        RepairPartCommands commandsWithAudit(AuditWriter audit) {
            HikariDataSource source = dataSource(); JdbcTemplate db = new JdbcTemplate(source);
            var transaction = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
            var idempotency = new IdempotencyExecutor(new JdbcIdempotencyStore(db), transaction,
                    new ObjectMapper().findAndRegisterModules());
            return new RepairPartCommands(new JdbcRepairOrderStore(db), new JdbcRepairPartRepository(db),
                    new RepairStockOperations(new JdbcStockMovementRepository(db)),
                    idempotency, transaction, audit);
        }
    }
}
