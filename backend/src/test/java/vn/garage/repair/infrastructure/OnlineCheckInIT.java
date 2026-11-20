package vn.garage.repair.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
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
import vn.garage.repair.application.StaffBookingQuery;
import vn.garage.repair.application.VehicleIntakeService;
import vn.garage.repair.domain.BookingSlotRepository;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

class OnlineCheckInIT {
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

    @Test @Timeout(30) void closedPastSlotCanBeCheckedInExactlyOnceAndAppearsOnLocalDateList() throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Fixture fixture = createFixture(db, token);
        try {
            var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
            var bookingStore = new JdbcBookingStore(db);
            var orderStore = new JdbcRepairOrderStore(db);
            BookingSlotRepository slotStore = new JdbcBookingSlotRepository(db);
            var intake = new VehicleIntakeService(bookingStore, slotStore, orderStore, writes,
                    Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var firstFuture = pool.submit(() -> { start.await(); return intake.checkIn(fixture.bookingId(), 0, 12000,
                        "Kính nứt", null, fixture.userId()); });
                var secondFuture = pool.submit(() -> { start.await(); return intake.checkIn(fixture.bookingId(), 0, 12000,
                        "Kính nứt", null, fixture.userId()); });
                start.countDown();
                var first = firstFuture.get(20, TimeUnit.SECONDS);
                var replay = secondFuture.get(20, TimeUnit.SECONDS);
                assertEquals(first.repairOrderId(), replay.repairOrderId());
                assertEquals("CHECKED_IN", db.queryForObject("SELECT status FROM dbo.bookings WHERE id=?", String.class, fixture.bookingId()));
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_orders WHERE booking_id=?", Integer.class, fixture.bookingId()));
                assertEquals(1, db.queryForObject("""
                        SELECT COUNT(*) FROM dbo.bookings WHERE slot_id=? AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
                        """, Integer.class, fixture.slotId()));
                assertEquals(12000, db.queryForObject("SELECT odometer_km FROM dbo.repair_orders WHERE id=?", Integer.class,
                        Long.parseLong(first.repairOrderId())));
                assertEquals("Kính nứt", db.queryForObject("SELECT condition_notes FROM dbo.repair_orders WHERE id=?", String.class,
                        Long.parseLong(first.repairOrderId())));

                var page = new StaffBookingQuery(bookingStore,
                        Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC))
                        .list(LocalDate.of(2020, 1, 1), "CHECKED_IN", 0, 20);
                assertEquals(1, page.totalElements());
                var summary = page.items().getFirst();
                assertEquals(Long.toString(fixture.bookingId()), summary.id());
                assertEquals(first.repairOrderId(), summary.repairOrderId());
                assertEquals("Dịch vụ kiểm thử", summary.requestedServices().getFirst().name());
            } finally {
                pool.shutdownNow();
            }
        } finally {
            cleanup(db, fixture);
        }
    }

    @Test void orderInsertFailureRollsBackBookingAndStaleSlotHintDoesNotCreateOrder() {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Fixture fixture = createFixture(db, token);
        long replacementSlot = db.queryForObject("""
                INSERT dbo.appointment_slots(starts_at,ends_at,vehicle_type,capacity,closed)
                OUTPUT INSERTED.id VALUES(?,?,'CAR',1,0)
                """, Long.class, OffsetDateTime.parse("2020-01-01T10:00:00+07:00"),
                OffsetDateTime.parse("2020-01-01T11:00:00+07:00"));
        try {
            var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
            var jdbcBookings = new JdbcBookingStore(db);
            var jdbcOrders = new JdbcRepairOrderStore(db);
            var slotStore = new JdbcBookingSlotRepository(db);
            var clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
            RepairOrderStore failingOrders = new RepairOrderStore() {
                @Override public java.util.Optional<Long> findBookingId(long id) { throw new UnsupportedOperationException(); }
                @Override public boolean markCompleted(long id, long version, long actor, OffsetDateTime at) { throw new UnsupportedOperationException(); }
                @Override public java.util.Optional<RepairOrder> findByBookingForUpdate(long bookingId) {
                    return jdbcOrders.findByBookingForUpdate(bookingId);
                }
                @Override public java.util.Optional<RepairOrderContext> findByIdForUpdate(long id) { return jdbcOrders.findByIdForUpdate(id); }
                @Override public java.util.Optional<RepairOrderContext> findById(long id) { return jdbcOrders.findById(id); }
                @Override public java.util.Optional<RepairOrderData> findDetails(long id) { return jdbcOrders.findDetails(id); }
                @Override public java.util.List<RepairOrderData> list(vn.garage.repair.domain.RepairStatus status, int page, int size) {
                    return jdbcOrders.list(status, page, size);
                }
                @Override public long count(vn.garage.repair.domain.RepairStatus status) { return jdbcOrders.count(status); }
                @Override public boolean updateHeader(long id, long version, boolean odometerPresent, Integer odometer,
                        boolean conditionPresent, String condition, boolean internalPresent, String internal) {
                    return jdbcOrders.updateHeader(id, version, odometerPresent, odometer,
                            conditionPresent, condition, internalPresent, internal);
                }
                @Override public RepairOrder create(NewRepairOrder order) {
                    jdbcOrders.create(order);
                    throw new IllegalStateException("injected insert failure");
                }
            };
            var failing = new VehicleIntakeService(jdbcBookings, slotStore, failingOrders, writes, clock);
            assertThrows(IllegalStateException.class,
                    () -> failing.checkIn(fixture.bookingId(), 0, null, null, null, fixture.userId()));
            assertEquals("CONFIRMED", db.queryForObject("SELECT status FROM dbo.bookings WHERE id=?", String.class, fixture.bookingId()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_orders WHERE booking_id=?", Integer.class, fixture.bookingId()));

            BookingStore movedAfterHint = new BookingStore() {
                @Override public boolean markCompleted(long id, long version, OffsetDateTime at) { throw new UnsupportedOperationException(); }
                @Override public java.util.Optional<BookingRef> readCheckInHint(long bookingId) {
                    var hint = jdbcBookings.readCheckInHint(bookingId);
                    db.update("UPDATE dbo.bookings SET slot_id=? WHERE id=?", replacementSlot, bookingId);
                    return hint;
                }
                @Override public java.util.Optional<LockedBooking> lockForCheckIn(long bookingId) { return jdbcBookings.lockForCheckIn(bookingId); }
                @Override public boolean markCheckedIn(long bookingId, long version, OffsetDateTime at) { return jdbcBookings.markCheckedIn(bookingId, version, at); }
                @Override public long createWalkIn(NewWalkInBooking booking, java.util.List<RequestedService> services) {
                    return jdbcBookings.createWalkIn(booking, services);
                }
                @Override public java.util.List<RequestedService> requestedServicesForBooking(long bookingId) {
                    return jdbcBookings.requestedServicesForBooking(bookingId);
                }
                @Override public BookingPage list(OffsetDateTime from, OffsetDateTime to, BookingStatus status, int page, int size) {
                    return jdbcBookings.list(from, to, status, page, size);
                }
            };
            var stale = new VehicleIntakeService(movedAfterHint, slotStore, jdbcOrders, writes, clock);
            assertThrows(VehicleIntakeService.StaleBookingVersion.class,
                    () -> stale.checkIn(fixture.bookingId(), 0, null, null, null, fixture.userId()));
            assertEquals(fixture.slotId(), db.queryForObject("SELECT slot_id FROM dbo.bookings WHERE id=?", Long.class, fixture.bookingId()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_orders WHERE booking_id=?", Integer.class, fixture.bookingId()));
        } finally {
            db.update("DELETE FROM dbo.appointment_slots WHERE id=?", replacementSlot);
            cleanup(db, fixture);
        }
    }

    static Fixture createFixture(JdbcTemplate db, String token) {
        return createFixture(db, token, OffsetDateTime.parse("2020-01-01T08:00:00+07:00"));
    }

    static Fixture createFixture(JdbcTemplate db, String token, OffsetDateTime starts) {
        String phone = "+849" + String.format(java.util.Locale.ROOT, "%08d", Math.abs(token.hashCode() % 100_000_000));
        long user = db.queryForObject("""
                INSERT dbo.users(username,password_hash,display_name,role) OUTPUT INSERTED.id VALUES(?,?,N'Nhân viên test','STAFF')
                """, Long.class, "staff_" + token, "hash_" + token);
        long customer = db.queryForObject("""
                INSERT dbo.customers(full_name,phone_normalized) OUTPUT INSERTED.id VALUES(N'Khách test',?)
                """, Long.class, phone);
        long vehicle = db.queryForObject("""
                INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized)
                OUTPUT INSERTED.id VALUES(?,'CAR',?,?)
                """, Long.class, customer, "51A" + token.substring(0, 5), "51A" + token.substring(0, 5));
        long service = db.queryForObject("""
                INSERT dbo.services(code,name,vehicle_type,reference_price,active)
                OUTPUT INSERTED.id VALUES(?,N'Dịch vụ kiểm thử','CAR',100000,1)
                """, Long.class, "SVC-" + token);
        long slot = db.queryForObject("""
                INSERT dbo.appointment_slots(starts_at,ends_at,vehicle_type,capacity,closed)
                OUTPUT INSERTED.id VALUES(?,?,'CAR',1,1)
                """, Long.class, starts, starts.plusHours(1));
        long booking = db.queryForObject("""
                INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                    contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot)
                OUTPUT INSERTED.id VALUES(?,?,?,?,'ONLINE','CONFIRMED','CAR',N'Khách test',?,?,?)
                """, Long.class, "BK-" + token, customer, vehicle, slot, phone,
                "51A" + token.substring(0, 5), "51A" + token.substring(0, 5));
        db.update("INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot) VALUES(?,?,N'Dịch vụ kiểm thử',100000)", booking, service);
        return new Fixture(user, customer, vehicle, service, slot, booking);
    }

    static void cleanup(JdbcTemplate db, Fixture f) {
        db.update("DELETE FROM dbo.repair_orders WHERE booking_id=?", f.bookingId());
        db.update("DELETE FROM dbo.booking_services WHERE booking_id=?", f.bookingId());
        db.update("DELETE FROM dbo.bookings WHERE id=?", f.bookingId());
        db.update("DELETE FROM dbo.appointment_slots WHERE id=?", f.slotId());
        db.update("DELETE FROM dbo.services WHERE id=?", f.serviceId());
        db.update("DELETE FROM dbo.vehicles WHERE id=?", f.vehicleId());
        db.update("DELETE FROM dbo.customers WHERE id=?", f.customerId());
        db.update("DELETE FROM dbo.users WHERE id=?", f.userId());
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource(); source.setUrl(url); source.setConnectionProperties(credentials); return source;
    }

    record Fixture(long userId, long customerId, long vehicleId, long serviceId, long slotId, long bookingId) { }
}
