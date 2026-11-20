package vn.garage.repair.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.garage.billing.application.InvoiceIssuance;
import vn.garage.billing.domain.InvoiceStore;
import vn.garage.billing.infrastructure.JdbcInvoiceStore;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.infrastructure.JdbcStockMovementRepository;
import vn.garage.repair.application.*;
import vn.garage.support.audit.JdbcAuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

import static org.junit.jupiter.api.Assertions.*;

class RepairCompletionIT {
    static HikariDataSource source;
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    @BeforeAll static void start() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("MIGRATION_TEST_URL"));
        config.setUsername(System.getenv("MIGRATION_TEST_USERNAME"));
        config.setPassword(System.getenv("MIGRATION_TEST_PASSWORD"));
        assertNotNull(config.getJdbcUrl(), "Set MIGRATION_TEST_URL to a dedicated database");
        config.setConnectionInitSql("""
                SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON;
                SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF;
                """);
        source = new HikariDataSource(config);
        String actual = new JdbcTemplate(source).queryForObject("SELECT DB_NAME()", String.class);
        assertNotNull(actual);
        assertTrue(actual.matches("garage_migration_test(?:_[a-z0-9]+)?"));
        Flyway.configure().dataSource(source).defaultSchema("dbo").cleanDisabled(true).load().migrate();
    }

    @AfterAll static void stop() { if (source != null) source.close(); }

    @Test void zeroInvoiceReplaysAndAppearsInDetail() {
        try (var run = new Run()) {
            var result = run.complete(0);
            assertEquals(result, run.complete(0));
            assertEquals(result.invoice(), run.query().detail(run.orderId).invoice());
            assertTrue(result.invoice().code().matches("INV-[A-Z0-9]{12}"));
            assertEquals("UNPAID", result.invoice().paymentStatus());
            assertEquals(0, result.invoice().totalAmount());
            assertEquals(1, run.invoiceCount());
            assertEquals(1, run.count("""
                    SELECT COUNT(*) FROM invoices WHERE repair_order_id=? AND paid_at IS NULL
                    AND paid_by IS NULL AND paid_amount IS NULL AND payment_method IS NULL
                    """, run.orderId));
            assertEquals(1, run.count("SELECT COUNT(*) FROM repair_orders WHERE id=? AND completed_by=?",
                    run.orderId, run.fixture.userId()));
        }
    }

    @Test void roundsEachLineAndKeepsInvoiceAmountAfterCatalogChanges() {
        try (var run = new Run()) {
            run.addLabor();
            run.parts.issue(run.orderId, run.partId, "0.500", "issue", run.fixture.userId(), "STAFF");
            var result = run.complete(2);
            assertEquals(102, result.invoice().totalAmount());
            run.db.update("UPDATE parts SET reference_price=500 WHERE id=?", run.partId);
            assertEquals(result, run.complete(0));
            assertEquals(102, run.query().detail(run.orderId).invoice().totalAmount());
        }
    }

    @Test void javaHalfUpLineRoundingMatchesSqlServerRoundAtBoundaries() {
        try (var run = new Run()) {
            var cases = java.util.List.of(
                    new RoundingCase("0.499", 101),
                    new RoundingCase("0.500", 101),
                    new RoundingCase("0.501", 101),
                    new RoundingCase("1.500", 1),
                    new RoundingCase("2.500", 1),
                    new RoundingCase("0.500", 0));

            for (var testCase : cases) {
                var quantity = new java.math.BigDecimal(testCase.quantity());
                var price = java.math.BigDecimal.valueOf(testCase.unitPrice());
                long javaAmount = new vn.garage.repair.domain.RepairTotals.Line(
                        vn.garage.repair.domain.RepairTotals.Type.PART, quantity, price, false)
                        .lineTotal().longValueExact();
                long sqlAmount = run.db.queryForObject("""
                        SELECT CAST(ROUND(CAST(? AS DECIMAL(18,3)) * CAST(? AS DECIMAL(18,0)), 0)
                                    AS BIGINT)
                        """, Long.class, testCase.quantity(), testCase.unitPrice());

                assertEquals(javaAmount, sqlAmount,
                        () -> "Java/SQL rounding differs for " + testCase.quantity() + " × " + testCase.unitPrice());
            }
        }
    }

    private record RoundingCase(String quantity, long unitPrice) { }

    @Test void ledgerMismatchPreventsCompletion() {
        try (var run = new Run()) {
            run.parts.issue(run.orderId, run.partId, "0.500", "issue", run.fixture.userId(), "STAFF");
            run.db.update("UPDATE repair_items SET quantity=1 WHERE repair_order_id=?", run.orderId);
            assertThrows(RepairCompletionService.InconsistentRepair.class, () -> run.complete(1));
            run.assertOpen();
        }
    }

    @Test void invoiceInsertFailureRollsBackBothStatusesAndInvoice() {
        try (var run = new Run()) {
            var service = run.completion(new JdbcInvoiceStore(run.db) {
                @Override public Invoice insertUnpaid(long id, String code, long amount, OffsetDateTime at) {
                    assertEquals("COMPLETED", run.status("bookings", run.fixture.bookingId()));
                    assertEquals("COMPLETED", run.status("repair_orders", id));
                    super.insertUnpaid(id, code, amount, at);
                    throw new IllegalStateException("Injected invoice write failure");
                }
            });
            assertThrows(IllegalStateException.class, () -> service.complete(run.orderId, 0, run.fixture.userId(), "STAFF"));
            run.assertOpen();
            assertEquals(0L, run.db.queryForObject("SELECT version FROM repair_orders WHERE id=?", Long.class, run.orderId));
            assertEquals(1L, run.db.queryForObject("SELECT version FROM bookings WHERE id=?", Long.class, run.fixture.bookingId()));
            assertEquals(0, run.count("SELECT COUNT(*) FROM repair_orders WHERE id=? AND completed_at IS NOT NULL", run.orderId));
        }
    }

    @Test void codeCollisionRollsBackAndRetriesWithNewCode() {
        try (var run = new Run()) {
            try (var existing = new Run()) {
                var usedCode = existing.complete(0).invoice().code();
                var attempts = new AtomicInteger();
                var service = run.completion(new JdbcInvoiceStore(run.db) {
                    @Override public Invoice insertUnpaid(long id, String code, long amount, OffsetDateTime at) {
                        return super.insertUnpaid(id, attempts.incrementAndGet() == 1 ? usedCode : code, amount, at);
                    }
                });
                var result = service.complete(run.orderId, 0, run.fixture.userId(), "STAFF");
                assertEquals(2, attempts.get());
                assertNotEquals(usedCode, result.invoice().code());
                assertEquals(1, run.invoiceCount());
            }
        }
    }

    static final class Run implements AutoCloseable {
        final JdbcTemplate db = new JdbcTemplate(source);
        final Clock clock = Clock.fixed(Instant.parse("2031-01-01T02:03:04Z"), ZoneOffset.UTC);
        final SqlServerWriteTransactions writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
        final JdbcBookingStore bookings = new JdbcBookingStore(db);
        final JdbcRepairOrderStore orders;
        final JdbcRepairPartRepository items = new JdbcRepairPartRepository(db);
        final JdbcRepairLaborRepository labor = new JdbcRepairLaborRepository(db);
        final RepairStockOperations stock = new RepairStockOperations(new JdbcStockMovementRepository(db));
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final OnlineCheckInIT.Fixture fixture;
        final long orderId;
        final long partId;
        final VehicleIntakeService intake;
        final RepairPartCommands parts;
        final RepairOrderService repair;

        Run() { this(new JdbcRepairOrderStore(new JdbcTemplate(source))); }

        Run(JdbcRepairOrderStore orders) {
            this.orders = orders;
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
            fixture = OnlineCheckInIT.createFixture(db, token,
                    OffsetDateTime.parse("2030-01-01T08:00:00+07:00").plusHours(NEXT_SLOT.getAndIncrement()));
            intake = new VehicleIntakeService(bookings, new JdbcBookingSlotRepository(db), orders, writes, clock);
            orderId = Long.parseLong(checkIn().repairOrderId());
            partId = db.queryForObject("""
                    INSERT parts(code,name,unit,vehicle_type,reference_price,stock_quantity)
                    OUTPUT INSERTED.id VALUES(?,N'Dầu',N'Lít','CAR',101,5)
                    """, Long.class, "SC11-" + token);
            parts = new RepairPartCommands(orders, items, stock,
                    new IdempotencyExecutor(new JdbcIdempotencyStore(db), writes, json),
                    writes, new JdbcAuditWriter(db, json));
            repair = new RepairOrderService(orders, labor, items, new vn.garage.catalog.application.ServiceCatalog(
                    db, new vn.garage.catalog.infrastructure.JdbcBookingServiceLookup(db)), writes, clock);
        }

        RepairCompletionService completion(InvoiceStore store) {
            return new RepairCompletionService(bookings, orders, items, stock, new InvoiceIssuance(store), writes, clock);
        }

        RepairCompletionService.Result complete(long version) {
            return completion(new JdbcInvoiceStore(db)).complete(orderId, version, fixture.userId(), "STAFF");
        }

        VehicleIntakeService.CheckInResult checkIn() {
            return intake.checkIn(fixture.bookingId(), 0, null, null, null, fixture.userId());
        }

        void addLabor() { repair.addLabor(orderId, null, "Công", "0.500", 101L, 0, fixture.userId(), "STAFF"); }

        RepairOrderQuery query() {
            return new RepairOrderQuery(orders, bookings, labor, new RepairPartQuery(orders, items, stock),
                    new InvoiceIssuance(new JdbcInvoiceStore(db)));
        }

        int count(String sql, Object... parameters) { return db.queryForObject(sql, Integer.class, parameters); }
        int invoiceCount() { return count("SELECT COUNT(*) FROM invoices WHERE repair_order_id=?", orderId); }
        String status(String table, long id) {
            return db.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id);
        }
        void assertOpen() {
            assertEquals("CHECKED_IN", status("bookings", fixture.bookingId()));
            assertEquals("IN_PROGRESS", status("repair_orders", orderId));
            assertEquals(0, invoiceCount());
        }

        @Override public void close() {
            db.update("DELETE FROM invoices WHERE repair_order_id=?", orderId);
            db.update("DELETE FROM stock_movements WHERE part_id=? AND movement_type='RETURN'", partId);
            db.update("DELETE FROM stock_movements WHERE part_id=?", partId);
            db.update("DELETE FROM repair_items WHERE repair_order_id=?", orderId);
            db.update("DELETE FROM parts WHERE id=?", partId);
            db.update("DELETE FROM idempotency_records WHERE scope IN (?,?)",
                    IdempotencyExecutor.scope("repair.part.issue", fixture.userId()),
                    IdempotencyExecutor.scope("repair.part.return", fixture.userId()));
            OnlineCheckInIT.cleanup(db, fixture);
        }
    }
}
