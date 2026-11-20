package vn.garage.billing.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.garage.billing.application.InvoiceIssuance;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.billing.application.InvoiceQuery;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.infrastructure.JdbcStockMovementRepository;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.repair.application.RepairInvoiceLines;
import vn.garage.repair.application.RepairOrderQuery;
import vn.garage.repair.application.RepairPartQuery;
import vn.garage.repair.infrastructure.JdbcBookingStore;
import vn.garage.repair.infrastructure.JdbcRepairLaborRepository;
import vn.garage.repair.infrastructure.JdbcRepairOrderStore;
import vn.garage.repair.infrastructure.JdbcRepairPartRepository;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.audit.JdbcAuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceSqlServerIT {
    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.parse("9999-12-31T23:59:59.999Z");
    private static final OffsetDateTime PAID_AT = OffsetDateTime.parse("2026-11-19T08:00:00.123Z");
    private static HikariDataSource source;

    @BeforeAll static void migrateDedicatedDatabase() {
        String url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to a dedicated SQL Server migration test database");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("MIGRATION_TEST_USERNAME"));
        config.setPassword(System.getenv("MIGRATION_TEST_PASSWORD"));
        config.setConnectionInitSql("""
                SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON;
                SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF;
                """);
        source = new HikariDataSource(config);
        String name = new JdbcTemplate(source).queryForObject("SELECT DB_NAME()", String.class);
        assertNotNull(name);
        assertTrue(name.matches("garage_migration_test(?:_[a-z0-9]+)?"));
        Flyway.configure().dataSource(source).defaultSchema("dbo").cleanDisabled(true).load().migrate();
    }

    @AfterAll static void close() {
        if (source != null) source.close();
    }

    @Test void paysResetsAndReplaysWithoutRevertingTheCurrentPayment() {
        try (var fixture = new PaymentFixture(150)) {
            var commands = fixture.commands(fixture.audit);
            var paid = commands.markPaid(fixture.invoice, 0, "CASH", "pay", fixture.actor, "ADMIN");
            assertEquals(PAID_AT, paid.body().paidAt());
            assertEquals(150, paid.body().paidAmount());
            assertEquals(Long.toString(fixture.actor), paid.body().paidBy());
            assertEquals(1, paid.body().version());
            assertEquals(1, fixture.auditCount());
            assertThrows(IdempotencyExecutor.IdempotencyConflictException.class,
                    () -> commands.markPaid(fixture.invoice, 0, "BANK_TRANSFER", "pay", fixture.actor, "ADMIN"));
            assertThrows(InvoicePaymentCommands.AlreadyPaid.class,
                    () -> commands.markPaid(fixture.invoice, 1, "CASH", "different", fixture.actor, "ADMIN"));

            var reset = commands.resetPayment(fixture.invoice, 1, "  Ghi nhầm  ", "pay", fixture.actor, "ADMIN");
            assertEquals("UNPAID", reset.body().paymentStatus());
            assertEquals(2, reset.body().version());
            assertNull(reset.body().paidAmount());
            assertNull(reset.body().paymentMethod());
            assertNull(reset.body().paidBy());
            assertNull(reset.body().paidAt());
            assertThrows(InvoicePaymentCommands.NotPaid.class,
                    () -> commands.resetPayment(fixture.invoice, 2, "Lý do", "different", fixture.actor, "ADMIN"));
            var resetReplay = commands.resetPayment(fixture.invoice, 1, "Ghi nhầm", "pay", fixture.actor, "ADMIN");
            assertTrue(resetReplay.replayed());
            assertEquals(reset.body(), resetReplay.body());
            assertTrue(commands.markPaid(fixture.invoice, 0, "CASH", "pay", fixture.actor, "ADMIN").replayed());
            assertEquals(2, fixture.store.findById(fixture.invoice).orElseThrow().version());

            var paidAgain = commands.markPaid(fixture.invoice, 2, "BANK_TRANSFER", "pay-again", fixture.actor, "ADMIN");
            assertEquals(3, paidAgain.body().version());
            assertEquals(paid.body(), commands.markPaid(fixture.invoice, 0, "CASH", "pay", fixture.actor, "ADMIN").body());
            assertEquals("BANK_TRANSFER", fixture.store.findById(fixture.invoice).orElseThrow().paymentMethod());
            assertEquals(PAID_AT, fixture.db.queryForObject("SELECT updated_at FROM dbo.invoices WHERE id=?",
                    OffsetDateTime.class, fixture.invoice));
            assertEquals(3, fixture.auditCount());
            assertEquals(3, fixture.keyCount());
            assertEquals(1, fixture.count("""
                    SELECT COUNT(*) FROM dbo.audit_logs WHERE entity_type='INVOICE' AND entity_id=?
                    AND action='INVOICE_RESET_PAYMENT' AND actor_user_id=? AND reason=N'Ghi nhầm'
                    AND JSON_VALUE(before_json,'$.id')=? AND JSON_VALUE(before_json,'$.totalAmount')='150'
                    AND JSON_VALUE(before_json,'$.paymentStatus')='PAID'
                    AND JSON_VALUE(before_json,'$.paidAmount')='150' AND JSON_VALUE(before_json,'$.paymentMethod')='CASH'
                    AND JSON_VALUE(before_json,'$.paidBy')=? AND JSON_VALUE(before_json,'$.paidAt') IS NOT NULL
                    AND JSON_VALUE(before_json,'$.version')='1' AND JSON_VALUE(after_json,'$.version')='2'
                    AND JSON_VALUE(after_json,'$.paymentStatus')='UNPAID' AND JSON_VALUE(after_json,'$.paidAmount') IS NULL
                    """, fixture.invoice, fixture.actor, Long.toString(fixture.invoice), Long.toString(fixture.actor)));
        }
    }

    @Test void auditFailureRollsBackAndTheSameKeyCanRetry() {
        try (var fixture = new PaymentFixture(0)) {
            var failing = fixture.commands((actor, action, type, id, reason, before, after) -> {
                fixture.audit.write(actor, action, type, id, reason, before, after);
                throw new IllegalStateException("Injected audit failure");
            });
            assertEquals("Injected audit failure", assertThrows(IllegalStateException.class,
                    () -> failing.markPaid(fixture.invoice, 0, "NO_CHARGE", "retry", fixture.actor, "ADMIN")).getMessage());
            var unchanged = fixture.store.findById(fixture.invoice).orElseThrow();
            assertEquals("UNPAID", unchanged.entry().invoice().paymentStatus());
            assertEquals(0, unchanged.version());
            assertNull(unchanged.paidAmount());
            assertEquals(0, fixture.auditCount());
            assertEquals(0, fixture.keyCount());
            var retry = fixture.commands(fixture.audit)
                    .markPaid(fixture.invoice, 0, "NO_CHARGE", "retry", fixture.actor, "ADMIN");
            assertEquals(0, retry.body().paidAmount());
            assertEquals("NO_CHARGE", retry.body().paymentMethod());
            assertFalse(retry.replayed());
        }
    }

    @Test void concurrentDifferentKeysWithTheSameVersionHaveOneWinner() throws Exception {
        try (var fixture = new PaymentFixture(150); var workers = Executors.newFixedThreadPool(2)) {
            var firstHasUpdated = new CountDownLatch(1);
            var secondReachedIdempotencyLock = new CountDownLatch(1);
            var first = fixture.commands((actor, action, type, id, reason, before, after) -> {
                firstHasUpdated.countDown();
                await(secondReachedIdempotencyLock);
                fixture.audit.write(actor, action, type, id, reason, before, after);
            });
            var secondKeys = new JdbcIdempotencyStore(fixture.db) {
                @Override public StoredResult lock(String scope, String key, OffsetDateTime now) {
                    secondReachedIdempotencyLock.countDown();
                    return super.lock(scope, key, now);
                }
            };
            var second = fixture.commands(secondKeys, fixture.audit);
            var winner = workers.submit(() -> first.markPaid(fixture.invoice, 0, "CASH", "first", fixture.actor, "ADMIN"));
            await(firstHasUpdated);
            var loser = workers.submit(() -> second.markPaid(fixture.invoice, 0, "NO_CHARGE", "second", fixture.actor, "ADMIN"));
            assertEquals(1, winner.get(15, TimeUnit.SECONDS).body().version());
            var failure = assertThrows(ExecutionException.class, () -> loser.get(15, TimeUnit.SECONDS));
            assertInstanceOf(InvoicePaymentCommands.StaleVersion.class, failure.getCause());
            assertEquals(1, fixture.auditCount());
            assertEquals(1, fixture.keyCount());
        }
    }

    @Test void lockTimeoutLeavesKeyAvailableForRetry() throws Exception {
        try (var fixture = new PaymentFixture(150); var worker = Executors.newSingleThreadExecutor()) {
            var commands = fixture.commands(fixture.audit);
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                fixture.store.lockById(fixture.invoice).orElseThrow();
                var blocked = worker.submit(() -> commands.markPaid(fixture.invoice, 0, "CASH", "timeout",
                        fixture.actor, "ADMIN"));
                var failure = assertThrows(ExecutionException.class, () -> blocked.get(15, TimeUnit.SECONDS));
                var timeout = assertInstanceOf(IdempotencyExecutor.RequestInProgressException.class, failure.getCause());
                assertEquals(1222, SqlServerWriteTransactions.sqlServerErrorCode(timeout));
            });
            assertEquals(0, fixture.keyCount());
            assertEquals(0, fixture.auditCount());
            assertFalse(commands.markPaid(fixture.invoice, 0, "CASH", "timeout", fixture.actor, "ADMIN").replayed());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Payment test did not reach synchronization point");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class PaymentFixture implements AutoCloseable {
        private final JdbcTemplate db = new JdbcTemplate(source);
        private final JdbcInvoiceStore store = new JdbcInvoiceStore(db);
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final AuditWriter audit = new JdbcAuditWriter(db, json);
        private final long actor;
        private final long customer;
        private final long vehicle;
        private final long order;
        private final long booking;
        private final long invoice;

        PaymentFixture(long total) {
            String token = token();
            actor = db.queryForObject("""
                    INSERT dbo.users(username,password_hash,display_name,role)
                    OUTPUT INSERTED.id VALUES(?,'hash',N'Nhân viên thu','ADMIN')
                    """, Long.class, "hd03-" + token);
            String phone = "+84" + String.format(Locale.ROOT, "%09d", Math.floorMod(token.hashCode(), 1_000_000_000));
            customer = db.queryForObject("""
                    INSERT dbo.customers(full_name,phone_normalized)
                    OUTPUT INSERTED.id VALUES(N'Khách test',?)
                    """, Long.class, phone);
            vehicle = db.queryForObject("""
                    INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized)
                    OUTPUT INSERTED.id VALUES(?,'CAR',?,?)
                    """, Long.class, customer, token, token);
            order = order(db, actor, customer, vehicle, token, phone, true);
            booking = db.queryForObject("SELECT booking_id FROM dbo.repair_orders WHERE id=?", Long.class, order);
            invoice = store.insertUnpaid(order, "INV-" + token(), total, PAID_AT.minusHours(1)).id();
        }

        InvoicePaymentCommands commands(AuditWriter writer) {
            return commands(new JdbcIdempotencyStore(db), writer);
        }

        InvoicePaymentCommands commands(JdbcIdempotencyStore keys, AuditWriter writer) {
            var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
            var idempotency = new IdempotencyExecutor(keys, writes, json);
            Clock clock = Clock.fixed(PAID_AT.toInstant().plusNanos(456_789), ZoneOffset.UTC);
            return new InvoicePaymentCommands(store, idempotency, writer, clock);
        }

        int auditCount() {
            return count("SELECT COUNT(*) FROM dbo.audit_logs WHERE entity_type='INVOICE' AND entity_id=?", invoice);
        }

        int keyCount() {
            return count("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope IN (?,?)",
                    IdempotencyExecutor.scope("billing.invoice.mark-paid", actor),
                    IdempotencyExecutor.scope("billing.invoice.reset-payment", actor));
        }

        int count(String sql, Object... args) {
            return db.queryForObject(sql, Integer.class, args);
        }

        @Override public void close() {
            db.update("DELETE FROM dbo.idempotency_records WHERE scope IN (?,?)",
                    IdempotencyExecutor.scope("billing.invoice.mark-paid", actor),
                    IdempotencyExecutor.scope("billing.invoice.reset-payment", actor));
            db.update("DELETE FROM dbo.audit_logs WHERE entity_type='INVOICE' AND entity_id=?", invoice);
            db.update("DELETE FROM dbo.invoices WHERE id=?", invoice);
            db.update("DELETE FROM dbo.repair_orders WHERE id=?", order);
            db.update("DELETE FROM dbo.bookings WHERE id=?", booking);
            db.update("DELETE FROM dbo.vehicles WHERE id=?", vehicle);
            db.update("DELETE FROM dbo.customers WHERE id=?", customer);
            db.update("DELETE FROM dbo.users WHERE id=?", actor);
        }
    }

    @Test void readsFilteredPagesAndCompletedSnapshotsAndRejectsInconsistentOrders() {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var db = new JdbcTemplate(source);
            String token = token();
            long actor = db.queryForObject("""
                    INSERT dbo.users(username,password_hash,display_name,role)
                    OUTPUT INSERTED.id VALUES(?,'hash',N'Nhân viên thu','STAFF')
                    """, Long.class, "hd02-" + token);
            String phone = "+84" + String.format(Locale.ROOT, "%09d", Math.floorMod(token.hashCode(), 1_000_000_000));
            long customer = db.queryForObject("""
                    INSERT dbo.customers(full_name,phone_normalized)
                    OUTPUT INSERTED.id VALUES(N'Khách test',?)
                    """, Long.class, phone);
            long vehicle = db.queryForObject("""
                    INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized)
                    OUTPUT INSERTED.id VALUES(?,'CAR',?,?)
                    """, Long.class, customer, token, token);
            var orders = new JdbcRepairOrderStore(db);
            var bookings = new JdbcBookingStore(db);
            var labor = new JdbcRepairLaborRepository(db);
            var parts = new JdbcRepairPartRepository(db);
            var stock = new RepairStockOperations(new JdbcStockMovementRepository(db));
            var store = new JdbcInvoiceStore(db);
            var query = new InvoiceQuery(store, new RepairInvoiceLines(orders, labor, parts),
                    ids -> Map.of(actor, "Nhân viên thu"));
            var repair = new RepairOrderQuery(orders, bookings, labor, new RepairPartQuery(orders, parts, stock),
                    new InvoiceIssuance(store));

            long order = order(db, actor, customer, vehicle, token, phone, true);
            long part = part(db, "Dầu cũ");
            long usedPartLine = parts.insertPartItem(order, part, "Dầu cũ", "Lít", 101).id();
            parts.updatePartQuantity(order, usedPartLine, new BigDecimal("0.500"));
            parts.insertPartItem(order, part(db, "Chưa dùng"), "Chưa dùng", "Lít", 101);
            long firstLabor = labor.insertLabor(order, null, "Công cũ", "giờ", new BigDecimal("0.500"), 101).id();
            long voided = labor.insertLabor(order, null, "Đã bỏ", "giờ", BigDecimal.ONE, 101).id();
            labor.voidLabor(order, voided, actor, ISSUED_AT);
            long secondLabor = labor.insertLabor(order, null, "Công khác", "giờ", new BigDecimal("0.499"), 101).id();
            var paid = store.insertUnpaid(order, "INV-" + token(), 152, ISSUED_AT);
            db.update("""
                    UPDATE dbo.invoices SET payment_status='PAID',paid_amount=total_amount,payment_method='CASH',
                    paid_by=?,paid_at=?,version=1 WHERE id=?
                    """, actor, ISSUED_AT, paid.id());
            db.update("UPDATE dbo.parts SET name=N'Dầu mới',reference_price=999 WHERE id=?", part);
            long zeroOrder = order(db, actor, customer, vehicle, token, phone, true);
            var zero = store.insertUnpaid(zeroOrder, "INV-" + token(), 0, ISSUED_AT);

            var firstPage = query.list(null, 0, 1);
            assertEquals(Long.toString(zero.id()), firstPage.items().getFirst().id());
            assertEquals(Long.toString(paid.id()), query.list(null, 1, 1).items().getFirst().id());
            var paidPage = query.list("PAID", 0, 1);
            var unpaidPage = query.list("UNPAID", 0, 1);
            assertEquals(Long.toString(paid.id()), paidPage.items().getFirst().id());
            assertEquals(Long.toString(zero.id()), unpaidPage.items().getFirst().id());
            assertEquals(firstPage.totalElements(), paidPage.totalElements() + unpaidPage.totalElements());
            assertTrue(query.list(null, Integer.MAX_VALUE, 100).items().isEmpty());

            var detail = query.detail(paid.id());
            assertEquals(List.of(firstLabor, secondLabor, usedPartLine).stream().map(String::valueOf).toList(),
                    detail.items().stream().map(RepairInvoiceLines.Line::id).toList());
            assertEquals(List.of(51L, 50L, 51L), detail.items().stream().map(RepairInvoiceLines.Line::lineTotal).toList());
            assertEquals("Dầu cũ", detail.items().getLast().description());
            assertEquals(101, detail.items().getLast().unitPrice());
            assertEquals("Nhân viên thu", detail.paidByDisplayName());
            assertEquals(152L, detail.paidAmount());
            assertEquals(detail.totalAmount(), repair.detail(order).totals().totalAmount());
            assertEquals(detail.paymentStatus(), repair.detail(order).invoice().paymentStatus());
            assertEquals(0, query.detail(zero.id()).totalAmount());
            assertTrue(query.detail(zero.id()).items().isEmpty());
            assertNull(query.detail(zero.id()).paidAmount());

            long missing = order(db, actor, customer, vehicle, token, phone, true);
            assertThrows(RepairCompletionService.InconsistentRepair.class, () -> repair.detail(missing));
            long progress = order(db, actor, customer, vehicle, token, phone, false);
            var invalid = store.insertUnpaid(progress, "INV-" + token(), 0, ISSUED_AT);
            assertThrows(RepairCompletionService.InconsistentRepair.class, () -> query.detail(invalid.id()));
        });
    }

    private static long order(JdbcTemplate db, long actor, long customer, long vehicle, String plate, String phone,
                              boolean completed) {
        long booking = db.queryForObject("""
                INSERT dbo.bookings(code,customer_id,vehicle_id,source,status,vehicle_type_snapshot,
                    contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                OUTPUT INSERTED.id VALUES(?,?,?,'WALK_IN',?,'CAR',N'Khách test',?,?,?,?)
                """, Long.class, "BK-" + token(), customer, vehicle, completed ? "COMPLETED" : "CHECKED_IN",
                phone, plate, plate, actor);
        return db.queryForObject("""
                INSERT dbo.repair_orders(booking_id,received_by,status,completed_by,completed_at,internal_notes)
                OUTPUT INSERTED.id VALUES(?,?,?,?,?,N'Ghi chú nội bộ')
                """, Long.class, booking, actor, completed ? "COMPLETED" : "IN_PROGRESS",
                completed ? actor : null, completed ? ISSUED_AT : null);
    }

    private static long part(JdbcTemplate db, String name) {
        return db.queryForObject("""
                INSERT dbo.parts(code,name,unit,vehicle_type,reference_price)
                OUTPUT INSERTED.id VALUES(?,?,N'Lít','CAR',101)
                """, Long.class, "HD02-" + token(), name);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
