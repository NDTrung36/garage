package vn.garage.repair.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.garage.catalog.application.ServiceCatalog;
import vn.garage.catalog.infrastructure.JdbcBookingServiceLookup;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.infrastructure.JdbcStockMovementRepository;
import vn.garage.repair.application.RepairOrderQuery;
import vn.garage.repair.application.RepairOrderService;
import vn.garage.repair.application.RepairPartQuery;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairLaborRepository;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.repair.domain.RepairTotals.Type;
import vn.garage.repair.domain.RepairTotals.Line;
import vn.garage.support.transaction.SqlServerWriteTransactions;

class RepairLaborIT {
    private static String url;
    private static Properties credentials;

    @BeforeAll static void prepareDedicatedDatabase() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to a dedicated SQL Server test database");
        credentials = new Properties();
        String user = System.getenv("MIGRATION_TEST_USERNAME");
        String password = System.getenv("MIGRATION_TEST_PASSWORD");
        if (user != null && !user.isBlank()) credentials.setProperty("user", user);
        if (password != null && !password.isBlank()) credentials.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection(url, credentials);
             var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DB_NAME()")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to use a non-test database");
        }
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @Test void laborRoundsByLineChecksVersionScopesItemsAndPreservesBookingSnapshots() {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Fixture fixture = createFixture(db, token);
        try {
            var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
            var orders = new JdbcRepairOrderStore(db);
            var labor = new JdbcRepairLaborRepository(db);
            RepairPartRepository items = new JdbcRepairPartRepository(db);
            var catalog = new ServiceCatalog(db, new JdbcBookingServiceLookup(db));
            var service = new RepairOrderService(orders, labor, items, catalog, writes,
                    Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC));

            var first = service.addLabor(fixture.orderId(), fixture.serviceId(), "Thay dầu", "0.500", 101L,
                    0, fixture.userId(), "STAFF");
            var second = service.addLabor(fixture.orderId(), null, "Công phát sinh", "0.500", 101L,
                    1, fixture.userId(), "STAFF");
            assertEquals(new java.math.BigDecimal("102"), second.totals().totalAmount());
            assertEquals(51, db.queryForObject("""
                    SELECT CAST(ROUND(quantity*unit_price,0) AS INT) FROM dbo.repair_items WHERE id=?
                    """, Integer.class, Long.parseLong(first.itemId())));
            assertEquals(102, db.queryForObject("""
                    SELECT CAST(SUM(ROUND(quantity*unit_price,0)) AS INT) FROM dbo.repair_items
                    WHERE repair_order_id=? AND item_type='LABOR' AND voided_at IS NULL
                    """, Integer.class, fixture.orderId()));
            var laborPatch = new RepairOrderService.LaborPatch(2, true, "Công thay dầu", true, "0.500", true, 101L);
            var updated = service.updateLabor(fixture.orderId(), Long.parseLong(first.itemId()), laborPatch,
                    fixture.userId(), "STAFF");
            assertEquals(3, updated.version());
            assertThrows(RepairOrderService.StaleVersion.class, () -> service.addLabor(fixture.orderId(), null,
                    "Cũ", "1", 1L, 0, fixture.userId(), "STAFF"));
            assertThrows(RepairOrderService.InvalidLaborInput.class, () -> service.addLabor(fixture.orderId(), null,
                    "Scale bốn", "0.0001", 1L, 3, fixture.userId(), "STAFF"));
            assertThrows(RepairOrderService.InvalidLaborInput.class, () -> service.addLabor(fixture.orderId(), null,
                    "Không", "0", 1L, 3, fixture.userId(), "STAFF"));
            assertThrows(RepairOrderService.InvalidLaborInput.class, () -> service.addLabor(fixture.orderId(), null,
                    "Âm", "-1", 1L, 3, fixture.userId(), "STAFF"));
            assertThrows(RepairOrderService.InvalidLaborInput.class, () -> service.addLabor(fixture.orderId(), null,
                    "Giá vượt", "1", 9_007_199_254_740_992L, 3, fixture.userId(), "STAFF"));
            assertThrows(RepairTotals.TotalLimitExceeded.class, () -> RepairTotals.calculate(List.of(
                    new Line(Type.LABOR, new java.math.BigDecimal("2"), RepairTotals.MAX_SAFE_AMOUNT, false))));
            assertThrows(RepairTotals.TotalLimitExceeded.class, () -> service.addLabor(fixture.orderId(), null,
                    "Tổng vượt", "2", RepairTotals.MAX_SAFE_AMOUNT.longValueExact(), 3,
                    fixture.userId(), "STAFF"));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_items WHERE repair_order_id=? AND item_type='LABOR'",
                    Integer.class, fixture.orderId()));
            assertEquals(3, db.queryForObject("SELECT version FROM dbo.repair_orders WHERE id=?", Integer.class, fixture.orderId()));

            var zeroPrice = service.addLabor(fixture.orderId(), null, "Miễn phí", "1.000", 0L,
                    3, fixture.userId(), "STAFF");
            assertEquals(102, zeroPrice.totals().totalAmount().intValueExact());
            long partItemId = db.queryForObject("""
                    INSERT dbo.repair_items(repair_order_id,item_type,part_id,description,unit_snapshot,quantity,unit_price)
                    OUTPUT INSERTED.id VALUES(?,'PART',?,N'Lọc dầu',N'Cái',0,50000)
                    """, Long.class, fixture.orderId(), fixture.partId());
            var patch = new RepairOrderService.LaborPatch(4, true, "Đổi tên", false, null, true, 0L);
            assertThrows(RepairOrderService.LaborRepositoryNotFound.class,
                    () -> service.updateLabor(fixture.orderId(), partItemId, patch, fixture.userId(), "STAFF"));
            long otherOrderLabor = db.queryForObject("""
                    INSERT dbo.repair_items(repair_order_id,item_type,description,unit_snapshot,quantity,unit_price)
                    OUTPUT INSERTED.id VALUES(?,'LABOR',N'Công việc phiếu khác',N'Lần',1,100)
                    """, Long.class, fixture.otherOrderId());
            assertThrows(RepairOrderService.LaborRepositoryNotFound.class,
                    () -> service.updateLabor(fixture.orderId(), otherOrderLabor, patch, fixture.userId(), "STAFF"));

            db.update("UPDATE dbo.services SET name=N'Tên catalog mới',reference_price=200000 WHERE id=?", fixture.serviceId());
            RepairOrderQuery query = query(db, orders, labor, items);
            var beforeVoid = query.detail(fixture.orderId());
            assertEquals("Thay dầu ban đầu", beforeVoid.requestedServices().getFirst().name());
            assertEquals(3, beforeVoid.laborItems().size());
            assertEquals(0, beforeVoid.partItems().getFirst().lineTotal());
            assertEquals("Công thay dầu", beforeVoid.laborItems().getFirst().description());

            long version = beforeVoid.orderVersion();
            service.patchHeader(fixture.orderId(), version, new RepairOrderService.HeaderPatch(
                    false, null, true, null, true, "  Ghi chú nội bộ  "), fixture.userId(), "STAFF");
            var afterHeader = query.detail(fixture.orderId());
            assertNull(afterHeader.conditionNotes());
            assertEquals("Ghi chú nội bộ", afterHeader.internalNotes());
            assertEquals("Thay dầu ban đầu", afterHeader.requestedServices().getFirst().name());

            service.voidLabor(fixture.orderId(), Long.parseLong(first.itemId()), afterHeader.orderVersion(),
                    fixture.userId(), "STAFF");
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_items WHERE id=? AND voided_at IS NOT NULL AND voided_by=?",
                    Integer.class, Long.parseLong(first.itemId()), fixture.userId()));
            var afterVoid = query.detail(fixture.orderId());
            assertEquals(2, afterVoid.laborItems().size());
            assertEquals(51, afterVoid.totals().totalAmount());

            db.update("""
                    UPDATE dbo.repair_orders SET status='COMPLETED',completed_by=?,completed_at=? WHERE id=?
                    """, fixture.userId(), OffsetDateTime.parse("2031-01-06T02:00:00Z"), fixture.orderId());
            assertThrows(RepairOrderService.RepairNotInProgress.class, () -> service.addLabor(fixture.orderId(), null,
                    "Sau chốt", "1", 1L, afterVoid.orderVersion(), fixture.userId(), "STAFF"));
        } finally {
            cleanup(db, fixture);
        }
    }

    private static RepairOrderQuery query(JdbcTemplate db, JdbcRepairOrderStore orders,
            JdbcRepairLaborRepository labor, RepairPartRepository items) {
        var movements = new JdbcStockMovementRepository(db);
        var partQuery = new RepairPartQuery(orders, items, new RepairStockOperations(movements));
        return new RepairOrderQuery(orders, new JdbcBookingStore(db), labor, partQuery,
                new vn.garage.billing.application.InvoiceIssuance(new vn.garage.billing.infrastructure.JdbcInvoiceStore(db)));
    }

    private static Fixture createFixture(JdbcTemplate db, String token) {
        String phone = "+849" + String.format(java.util.Locale.ROOT, "%08d", Math.abs(token.hashCode() % 100_000_000));
        long user = db.queryForObject("""
                INSERT dbo.users(username,password_hash,display_name,role) OUTPUT INSERTED.id VALUES(?,?,N'Repair test','STAFF')
                """, Long.class, "labor_" + token, "hash_" + token);
        long customer = db.queryForObject("INSERT dbo.customers(full_name,phone_normalized) OUTPUT INSERTED.id VALUES(N'Khách sửa',?)",
                Long.class, phone);
        long vehicle = db.queryForObject("""
                INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized)
                OUTPUT INSERTED.id VALUES(?,'CAR',N'51A12345','51A12345')
                """, Long.class, customer);
        long service = db.queryForObject("""
                INSERT dbo.services(code,name,vehicle_type,reference_price,active)
                OUTPUT INSERTED.id VALUES(?,N'Thay dầu ban đầu','CAR',100000,1)
                """, Long.class, "LABSVC-" + token);
        long booking = db.queryForObject("""
                INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                    contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                OUTPUT INSERTED.id VALUES(?,?,?,NULL,'WALK_IN','CHECKED_IN','CAR',N'Khách sửa',?,N'51A12345',N'51A12345',?)
                """, Long.class, "BK-LAB-" + token, customer, vehicle, phone, user);
        db.update("INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot) VALUES(?,?,N'Thay dầu ban đầu',100000)", booking, service);
        long order = db.queryForObject("""
                INSERT dbo.repair_orders(booking_id,received_by,received_at)
                OUTPUT INSERTED.id VALUES(?,?,?)
                """, Long.class, booking, user, OffsetDateTime.parse("2031-01-06T01:00:00Z"));
        long otherBooking = db.queryForObject("""
                INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                    contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                OUTPUT INSERTED.id VALUES(?,?,?,NULL,'WALK_IN','CHECKED_IN','CAR',N'Khách sửa',?,N'51A12345',N'51A12345',?)
                """, Long.class, "BK-LB2-" + token, customer, vehicle, phone, user);
        long otherOrder = db.queryForObject("""
                INSERT dbo.repair_orders(booking_id,received_by,received_at)
                OUTPUT INSERTED.id VALUES(?,?,?)
                """, Long.class, otherBooking, user, OffsetDateTime.parse("2031-01-06T01:00:00Z"));
        long part = db.queryForObject("""
                INSERT dbo.parts(code,name,unit,vehicle_type,reference_price)
                OUTPUT INSERTED.id VALUES(?,N'Lọc dầu test',N'Cái','CAR',50000)
                """, Long.class, "LABPART-" + token);
        return new Fixture(user, customer, vehicle, service, booking, order, otherBooking, otherOrder, part);
    }

    private static void cleanup(JdbcTemplate db, Fixture f) {
        db.update("DELETE FROM dbo.repair_items WHERE repair_order_id IN (?,?)", f.orderId(), f.otherOrderId());
        db.update("DELETE FROM dbo.repair_orders WHERE id=?", f.otherOrderId());
        db.update("DELETE FROM dbo.repair_orders WHERE id=?", f.orderId());
        db.update("DELETE FROM dbo.booking_services WHERE booking_id=?", f.bookingId());
        db.update("DELETE FROM dbo.bookings WHERE id=?", f.otherBookingId());
        db.update("DELETE FROM dbo.bookings WHERE id=?", f.bookingId());
        db.update("DELETE FROM dbo.parts WHERE id=?", f.partId());
        db.update("DELETE FROM dbo.services WHERE id=?", f.serviceId());
        db.update("DELETE FROM dbo.vehicles WHERE id=?", f.vehicleId());
        db.update("DELETE FROM dbo.customers WHERE id=?", f.customerId());
        db.update("DELETE FROM dbo.users WHERE id=?", f.userId());
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource(); source.setUrl(url); source.setConnectionProperties(credentials); return source;
    }

    private record Fixture(long userId, long customerId, long vehicleId, long serviceId, long bookingId,
                           long orderId, long otherBookingId, long otherOrderId, long partId) { }
}
