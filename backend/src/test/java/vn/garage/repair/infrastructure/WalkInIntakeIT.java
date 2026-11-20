package vn.garage.repair.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.garage.catalog.application.ServiceCatalog;
import vn.garage.catalog.infrastructure.JdbcBookingServiceLookup;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.customer.infrastructure.JdbcCustomerVehicleStore;
import vn.garage.repair.application.VehicleIntakeService;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

class WalkInIntakeIT {
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

    @Test @Timeout(30) void idempotentWalkInReusesCustomerWithoutChangingCapacityAndRollsBackFailures() {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Fixture fixture = createFixture(db, token);
        try {
            VehicleIntakeService intake = intake(source, db, fixture.userId());
            var firstContact = ContactAndVehicle.from("Nguyễn Văn An", fixture.firstLocalPhone(), "CAR",
                    "51a-123.45", "Honda", "Civic");
            var firstCommand = new VehicleIntakeService.WalkInCommand(firstContact,
                    List.of(fixture.carServiceId()), "  Nhận tại quầy  ", 12500, "Kính nứt", null);
            long occupiedBefore = db.queryForObject("""
                    SELECT COUNT_BIG(*) FROM dbo.bookings WHERE slot_id IS NOT NULL
                      AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
                    """, Long.class);
            var created = intake.walkIn(firstCommand, "walk-" + token, fixture.userId());
            var retry = intake.walkIn(firstCommand, "walk-" + token, fixture.userId());
            assertEquals(201, created.httpStatus());
            assertEquals(created.body(), retry.body());
            assertFalse(created.replayed());
            assertTrue(retry.replayed());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.bookings WHERE code=?", Integer.class,
                    created.body().bookingCode()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.repair_orders WHERE booking_id=?", Integer.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals("WALK_IN", db.queryForObject("SELECT source FROM dbo.bookings WHERE id=?", String.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals("CHECKED_IN", db.queryForObject("SELECT status FROM dbo.bookings WHERE id=?", String.class,
                    Long.parseLong(created.body().bookingId())));
            assertNull(db.queryForObject("SELECT slot_id FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals(fixture.userId(), db.queryForObject("SELECT created_by FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(created.body().bookingId())));
            long firstCustomer = db.queryForObject("SELECT customer_id FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(created.body().bookingId()));
            long firstVehicle = db.queryForObject("SELECT vehicle_id FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(created.body().bookingId()));
            assertEquals(fixture.firstE164Phone(), db.queryForObject("SELECT contact_phone_snapshot FROM dbo.bookings WHERE id=?", String.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals("51A12345", db.queryForObject("SELECT plate_normalized_snapshot FROM dbo.bookings WHERE id=?", String.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals(fixture.userId(), db.queryForObject("SELECT received_by FROM dbo.repair_orders WHERE booking_id=?", Long.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals(12500, db.queryForObject("SELECT odometer_km FROM dbo.repair_orders WHERE booking_id=?", Integer.class,
                    Long.parseLong(created.body().bookingId())));
            assertEquals(occupiedBefore, db.queryForObject("""
                    SELECT COUNT_BIG(*) FROM dbo.bookings WHERE slot_id IS NOT NULL
                      AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
                    """, Long.class));

            var formattedE164 = "+84 " + fixture.firstE164Phone().substring(3, 6) + " "
                    + fixture.firstE164Phone().substring(6, 9) + " " + fixture.firstE164Phone().substring(9);
            var secondContact = ContactAndVehicle.from("Tên mới không ghi đè", formattedE164, "CAR",
                    "51B-222.33", null, null);
            var second = intake.walkIn(new VehicleIntakeService.WalkInCommand(secondContact,
                    List.of(fixture.carServiceId()), null, null, null, null), "walk2-" + token, fixture.userId());
            long secondCustomer = db.queryForObject("SELECT customer_id FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(second.body().bookingId()));
            long secondVehicle = db.queryForObject("SELECT vehicle_id FROM dbo.bookings WHERE id=?", Long.class,
                    Long.parseLong(second.body().bookingId()));
            assertEquals(firstCustomer, secondCustomer);
            assertNotEquals(firstVehicle, secondVehicle);
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM dbo.vehicles WHERE customer_id=?", Integer.class, firstCustomer));
            assertEquals("Nguyễn Văn An", db.queryForObject("SELECT full_name FROM dbo.customers WHERE id=?", String.class, firstCustomer));

            var wrongType = ContactAndVehicle.from("Khách khác", fixture.wrongLocalPhone(), "MOTORBIKE",
                    "59M-888.88", null, null);
            int customersBeforeInvalid = db.queryForObject("SELECT COUNT(*) FROM dbo.customers", Integer.class);
            assertThrows(IllegalArgumentException.class, () -> intake.walkIn(new VehicleIntakeService.WalkInCommand(
                    wrongType, List.of(fixture.carServiceId()), null, null, null, null), "bad-" + token, fixture.userId()));
            assertEquals(customersBeforeInvalid, db.queryForObject("SELECT COUNT(*) FROM dbo.customers", Integer.class));

            ContactAndVehicle rollbackContact = ContactAndVehicle.from("Khách rollback", fixture.rollbackLocalPhone(), "CAR",
                    "60A-777.77", null, null);
            String rollbackKey = "rollback-" + token;
            RepairOrderStore failBeforeOrder = new RepairOrderStore() {
                @Override public java.util.Optional<Long> findBookingId(long id) { throw new UnsupportedOperationException(); }
                @Override public boolean markCompleted(long id, long version, long actor, OffsetDateTime at) { throw new UnsupportedOperationException(); }
                @Override public java.util.Optional<RepairOrder> findByBookingForUpdate(long bookingId) {
                    throw new UnsupportedOperationException();
                }
                @Override public java.util.Optional<RepairOrderContext> findByIdForUpdate(long id) {
                    throw new UnsupportedOperationException();
                }
                @Override public java.util.Optional<RepairOrderContext> findById(long id) {
                    throw new UnsupportedOperationException();
                }
                @Override public java.util.Optional<RepairOrderData> findDetails(long id) {
                    throw new UnsupportedOperationException();
                }
                @Override public java.util.List<RepairOrderData> list(vn.garage.repair.domain.RepairStatus status,
                                                                       int page, int size) {
                    throw new UnsupportedOperationException();
                }
                @Override public long count(vn.garage.repair.domain.RepairStatus status) {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean updateHeader(long id, long version, boolean odometerPresent, Integer odometer,
                        boolean conditionPresent, String condition, boolean internalPresent, String internal) {
                    throw new UnsupportedOperationException();
                }
                @Override public RepairOrder create(NewRepairOrder order) { throw new IllegalStateException("injected before order insert"); }
            };
            VehicleIntakeService failing = intake(source, db, fixture.userId(), failBeforeOrder);
            assertThrows(IllegalStateException.class, () -> failing.walkIn(new VehicleIntakeService.WalkInCommand(
                    rollbackContact, List.of(fixture.carServiceId()), null, null, null, null), rollbackKey, fixture.userId()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.customers WHERE phone_normalized=?", Integer.class,
                    rollbackContact.phoneE164()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.bookings WHERE contact_phone_snapshot=?", Integer.class,
                    rollbackContact.phoneE164()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    Integer.class, IdempotencyExecutor.scope("repair.walkin.create", fixture.userId()), rollbackKey));
        } finally {
            cleanup(db, fixture);
        }
    }

    private static VehicleIntakeService intake(DriverManagerDataSource source, JdbcTemplate db, long actor) {
        return intake(source, db, actor, new JdbcRepairOrderStore(db));
    }

    private static VehicleIntakeService intake(DriverManagerDataSource source, JdbcTemplate db, long actor,
                                               RepairOrderStore orderStore) {
        var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
        var clock = Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC);
        var executor = new IdempotencyExecutor(new JdbcIdempotencyStore(db), writes,
                new ObjectMapper().registerModule(new JavaTimeModule()), clock);
        var customers = new CustomerVehicleOnboarding(new JdbcCustomerVehicleStore(db));
        var catalog = new ServiceCatalog(db, new JdbcBookingServiceLookup(db));
        return new VehicleIntakeService(new JdbcBookingStore(db), new JdbcBookingSlotRepository(db), orderStore,
                writes, clock, executor, customers, catalog);
    }

    private static Fixture createFixture(JdbcTemplate db, String token) {
        long user = db.queryForObject("""
                INSERT dbo.users(username,password_hash,display_name,role) OUTPUT INSERTED.id VALUES(?,?,N'Nhân viên walk-in','STAFF')
                """, Long.class, "walkin_" + token, "hash_" + token);
        long carService = db.queryForObject("""
                INSERT dbo.services(code,name,vehicle_type,reference_price,active)
                OUTPUT INSERTED.id VALUES(?,N'Dịch vụ xe hơi','CAR',150000,1)
                """, Long.class, "WICAR-" + token);
        return new Fixture(user, carService, localPhone(token, 0), e164(localPhone(token, 0)),
                localPhone(token, 1), localPhone(token, 2));
    }

    private static void cleanup(JdbcTemplate db, Fixture fixture) {
        String scope = IdempotencyExecutor.scope("repair.walkin.create", fixture.userId());
        String phones = "'" + fixture.firstE164Phone() + "','" + e164(fixture.wrongLocalPhone())
                + "','" + e164(fixture.rollbackLocalPhone()) + "'";
        db.update("DELETE FROM dbo.repair_orders WHERE booking_id IN (SELECT id FROM dbo.bookings WHERE created_by=?)", fixture.userId());
        db.update("DELETE FROM dbo.booking_services WHERE booking_id IN (SELECT id FROM dbo.bookings WHERE created_by=?)", fixture.userId());
        db.update("DELETE FROM dbo.bookings WHERE created_by=?", fixture.userId());
        db.update("DELETE FROM dbo.vehicles WHERE customer_id IN (SELECT id FROM dbo.customers WHERE phone_normalized IN (" + phones + "))");
        db.update("DELETE FROM dbo.customers WHERE phone_normalized IN (" + phones + ")");
        db.update("DELETE FROM dbo.idempotency_records WHERE scope=?", scope);
        db.update("DELETE FROM dbo.services WHERE id=?", fixture.carServiceId());
        db.update("DELETE FROM dbo.users WHERE id=?", fixture.userId());
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource(); source.setUrl(url); source.setConnectionProperties(credentials); return source;
    }

    private static String localPhone(String token, int offset) {
        return "09" + String.format(java.util.Locale.ROOT, "%08d", Math.floorMod(token.hashCode() + offset, 100_000_000));
    }
    private static String e164(String local) { return "+84" + local.substring(1); }

    private record Fixture(long userId, long carServiceId, String firstLocalPhone, String firstE164Phone,
                           String wrongLocalPhone, String rollbackLocalPhone) { }
}
