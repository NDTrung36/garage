package vn.garage.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import vn.garage.catalog.application.ServiceCatalog;
import vn.garage.repair.application.SlotGenerator;
import vn.garage.repair.domain.SlotPlan.Hours;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class FoundationMigrationIT {
    private static final String HASH = "a".repeat(64);
    private static final String CONNECTION_SETTINGS = """
            SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON;
            SET ARITHABORT ON; SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON;
            SET NUMERIC_ROUNDABORT OFF; SET XACT_ABORT OFF; SET LOCK_TIMEOUT 5000;
            """;
    private static final Map<String, String> CONTRACT = Map.ofEntries(
            Map.entry("users", "id:bigint username:nvarchar(100) password_hash:varchar(255) display_name:nvarchar(100) role:varchar(10) active:bit auth_version:bigint created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("customers", "id:bigint full_name:nvarchar(100) phone_normalized:varchar(20) created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("vehicles", "id:bigint customer_id:bigint vehicle_type:varchar(10) plate_display:nvarchar(20) plate_normalized:nvarchar(20) brand:nvarchar(100)? model:nvarchar(100)? created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("services", "id:bigint code:varchar(40) name:nvarchar(150) description:nvarchar(1000)? vehicle_type:varchar(10) reference_price:decimal(18,0) active:bit created_at:datetimeoffset(3) updated_at:datetimeoffset(3) version:bigint"),
            Map.entry("garage_settings", "id:tinyint timezone:varchar(50) slot_minutes:int car_capacity:int motorbike_capacity:int minimum_lead_minutes:int maximum_advance_days:int generated_through:date? version:bigint created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("working_hours", "id:bigint day_of_week:tinyint opens_at:time(0) closes_at:time(0) created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("calendar_exceptions", "id:bigint closed_date:date reason:nvarchar(500) created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("appointment_slots", "id:bigint starts_at:datetimeoffset(3) ends_at:datetimeoffset(3) vehicle_type:varchar(10) capacity:int closed:bit version:bigint created_at:datetimeoffset(3) updated_at:datetimeoffset(3) manual_closed:bit capacity_override:int?"),
            Map.entry("bookings", "id:bigint code:varchar(24) customer_id:bigint vehicle_id:bigint slot_id:bigint? source:varchar(10) status:varchar(20) vehicle_type_snapshot:varchar(10) contact_name_snapshot:nvarchar(100) contact_phone_snapshot:varchar(20) plate_snapshot:nvarchar(20) plate_normalized_snapshot:nvarchar(20) brand_snapshot:nvarchar(100)? model_snapshot:nvarchar(100)? customer_note:nvarchar(1000)? created_by:bigint? status_reason:nvarchar(500)? version:bigint created_at:datetimeoffset(3) updated_at:datetimeoffset(3)"),
            Map.entry("booking_services", "id:bigint booking_id:bigint service_id:bigint service_name_snapshot:nvarchar(150) reference_price_snapshot:decimal(18,0)"),
            Map.entry("idempotency_records", "scope:varchar(100) request_key:varchar(100) request_hash:char(64) http_status:int response_json:nvarchar(max) created_at:datetimeoffset(3) expires_at:datetimeoffset(3)")
    );
    private static String url;
    private static Properties credentials;
    private static Flyway flyway;

    @BeforeAll
    static void migrateDedicatedDatabase() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to a dedicated SQL Server database named garage_migration_test or garage_migration_test_<suffix>");
        assertTrue(url.startsWith("jdbc:sqlserver://"), "SQL Server JDBC URL is required");
        credentials = new Properties();
        for (String field : new String[]{"user", "password"}) {
            String value = System.getenv(field.equals("user") ? "MIGRATION_TEST_USERNAME" : "MIGRATION_TEST_PASSWORD");
            if (value != null && !value.isBlank()) credentials.setProperty(field, value);
        }
        try (Connection c = connect()) {
            assertTrue(scalar(c, "SELECT DB_NAME()").matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to migrate a database outside the dedicated test-name pattern");
            Set<String> tables = tables(c);
            Set<String> migrated = new HashSet<>(CONTRACT.keySet());
            migrated.add("flyway_schema_history");
            assertTrue(tables.isEmpty() || tables.equals(migrated),
                    "Use an empty dedicated database or the exact foundation schema from a previous test run");
        }
        var configuration = Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo")
                .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true);
        flyway = configuration.load();
        flyway.migrate();
        flyway.validate();
    }

    @Test
    void migrationsAreValidatedAndNotReapplied() throws Exception {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (Connection c = connect()) {
            assertEquals("4", scalar(c, "SELECT COUNT(*) FROM dbo.flyway_schema_history WHERE success = 1"));
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM dbo.garage_settings"));
            assertEquals("12", scalar(c, "SELECT COUNT(*) FROM dbo.working_hours"));
            assertEquals("60:1:2:60:14:Asia/Ho_Chi_Minh", scalar(c, "SELECT CONCAT(slot_minutes,':',car_capacity,':',motorbike_capacity,':',minimum_lead_minutes,':',maximum_advance_days,':',timezone) FROM dbo.garage_settings WHERE id=1 AND generated_through IS NULL"));
            assertEquals("12", scalar(c, "SELECT COUNT(*) FROM dbo.working_hours WHERE day_of_week BETWEEN 1 AND 6 AND ((opens_at='08:00' AND closes_at='12:00') OR (opens_at='13:00' AND closes_at='17:00'))"));
            for (String table : CONTRACT.keySet()) {
                if (!Set.of("garage_settings", "working_hours").contains(table))
                    assertEquals("0", scalar(c, "SELECT COUNT(*) FROM dbo." + table), "No demo data: " + table);
            }
        }
    }

    @Test
    void schemaMatchesTheFoundationContract() throws Exception {
        try (Connection c = connect()) {
            Set<String> expectedTables = new HashSet<>(CONTRACT.keySet());
            expectedTables.add("flyway_schema_history");
            assertEquals(expectedTables, tables(c));
            for (var table : CONTRACT.entrySet()) {
                Map<String, String> actual = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT col.name, typ.name, col.max_length, col.precision, col.scale, col.is_nullable, col.is_identity
                        FROM sys.columns col JOIN sys.types typ ON col.user_type_id=typ.user_type_id
                        WHERE col.object_id=OBJECT_ID(?)
                        """)) {
                    ps.setString(1, "dbo." + table.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String type = rs.getString(2);
                            int length = rs.getInt(3);
                            type += switch (type) {
                                case "varchar", "char", "nvarchar" -> "(" + (length == -1 ? "max" : Integer.toString(type.equals("nvarchar") ? length / 2 : length)) + ")";
                                case "decimal" -> "(" + rs.getInt(4) + "," + rs.getInt(5) + ")";
                                case "time", "datetimeoffset" -> "(" + rs.getInt(5) + ")";
                                default -> "";
                            };
                            actual.put(rs.getString(1), type + (rs.getBoolean(6) ? "?" : ""));
                            boolean expectedIdentity = rs.getString(1).equals("id") && !table.getKey().equals("garage_settings");
                            assertEquals(expectedIdentity, rs.getBoolean(7), table.getKey() + "." + rs.getString(1));
                        }
                    }
                }
                Map<String, String> expected = new HashMap<>();
                for (String field : table.getValue().split(" ")) {
                    String[] pair = field.split(":", 2);
                    expected.put(pair[0], pair[1]);
                }
                assertEquals(expected, actual, table.getKey());
            }
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sys.foreign_keys WHERE delete_referential_action<>0 OR update_referential_action<>0 OR is_disabled=1 OR is_not_trusted=1"));
            assertEquals("7", scalar(c, "SELECT COUNT(*) FROM sys.foreign_keys"));
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.bookings') AND name='UX_bookings_slot_plate_occupied' AND is_unique=1 AND has_filter=1"));
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.idempotency_records') AND name='IX_idempotency_records_expiry'"));
        }
    }

    @Test
    void customerAndVehicleUniquenessPreservesContacts() throws Exception {
        inTransaction(c -> {
            long first = customer(c, "+84912345678");
            assertEquals("Khách kiểm thử", scalar(c, "SELECT full_name FROM dbo.customers WHERE id=?", first));
            assertEquals("0:0", scalar(c, "SELECT CONCAT(DATEPART(TZOFFSET,created_at),':',DATEPART(TZOFFSET,updated_at)) FROM dbo.customers WHERE id=?", first));
            reject(c, Set.of(2601, 2627), "INSERT dbo.customers(full_name,phone_normalized) VALUES(N'Khách khác','+84912345678')");
            reject(c, Set.of(547), "INSERT dbo.customers(full_name,phone_normalized) VALUES(N'Khách khác','0912345678')");
            vehicle(c, first);
            reject(c, Set.of(2601, 2627), "INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized) VALUES(?,'MOTORBIKE',N'59X-123.45',N'59X12345')", first);
            long second = customer(c, "+84987654321");
            assertTrue(vehicle(c, second) > 0, "The same plate may be associated with another contact");
            reject(c, Set.of(547), "INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized) VALUES(-1,'CAR',N'30A-123.45',N'30A12345')");
        });
    }

    @Test
    void bookingCannotUseAnotherCustomersVehicle() throws Exception {
        inTransaction(c -> {
            long owner = customer(c, "+84912345678");
            long another = customer(c, "+84987654321");
            long vehicle = vehicle(c, owner);
            long slot = slot(c);
            assertSqlError(Set.of(547), () -> booking(c, "BK-WRONGOWNER", another, vehicle, slot, "ONLINE", "CONFIRMED", null));
        });
    }

    @Test
    void duplicatePlateIsBlockedAcrossCustomersAndOccupiedStates() throws Exception {
        inTransaction(c -> {
            long first = customer(c, "+84912345678");
            long second = customer(c, "+84987654321");
            long v1 = vehicle(c, first), v2 = vehicle(c, second), slot = slot(c);
            long original = booking(c, "BK-ORIGINAL", first, v1, slot, "ONLINE", "CONFIRMED", null);
            for (String state : new String[]{"CONFIRMED", "CHECKED_IN", "COMPLETED"}) {
                execute(c, "UPDATE dbo.bookings SET status=? WHERE id=?", state, original);
                assertSqlError(Set.of(2601, 2627), () -> booking(c, "BK-DUPLICATE", second, v2, slot, "ONLINE", "CONFIRMED", null));
            }
            booking(c, "BK-CANCELLED", second, v2, slot, "ONLINE", "CANCELLED", null);
            booking(c, "BK-NOSHOW", second, v2, slot, "ONLINE", "NO_SHOW", null);
            execute(c, "UPDATE dbo.bookings SET status='CANCELLED' WHERE id=?", original);
            booking(c, "BK-REPLACEMENT", second, v2, slot, "ONLINE", "CONFIRMED", null);
        });
    }

    @Test
    void walkInNeedsAStaffCreatorButDoesNotReserveAnOnlineSlot() throws Exception {
        inTransaction(c -> {
            long customer = customer(c, "+84912345678"), vehicle = vehicle(c, customer), slot = slot(c);
            long staff = id(c, "INSERT dbo.users(username,password_hash,display_name,role) OUTPUT INSERTED.id VALUES(N'it-staff','test-hash',N'Nhân viên','STAFF')");
            booking(c, "BK-WALKIN1", customer, vehicle, null, "WALK_IN", "CHECKED_IN", staff);
            booking(c, "BK-WALKIN2", customer, vehicle, null, "WALK_IN", "COMPLETED", staff);
            assertSqlError(Set.of(547), () -> booking(c, "BK-BADSLOT", customer, vehicle, slot, "WALK_IN", "CHECKED_IN", staff));
            assertSqlError(Set.of(547), () -> booking(c, "BK-NOSTAFF", customer, vehicle, null, "WALK_IN", "CHECKED_IN", null));
            assertSqlError(Set.of(547), () -> booking(c, "BK-NOSLOT", customer, vehicle, null, "ONLINE", "CONFIRMED", null));
            assertEquals("1:0", scalar(c, "SELECT CONCAT(active,':',auth_version) FROM dbo.users WHERE id=?", staff));
            assertEquals("0:0", scalar(c, "SELECT CONCAT(closed,':',version) FROM dbo.appointment_slots WHERE id=?", slot));
        });
    }

    @Test
    void bookingServicesRequireValidReferencesAndCannotRepeat() throws Exception {
        inTransaction(c -> {
            long customer = customer(c, "+84912345678"), vehicle = vehicle(c, customer), slot = slot(c);
            long booking = booking(c, "BK-SERVICES", customer, vehicle, slot, "ONLINE", "CONFIRMED", null);
            long service = id(c, "INSERT dbo.services(code,name,vehicle_type,reference_price) OUTPUT INSERTED.id VALUES('IT-SERVICE',N'Kiểm tra xe','BOTH',0)");
            execute(c, "INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot) VALUES(?,?,N'Kiểm tra xe',0)", booking, service);
            reject(c, Set.of(2601, 2627), "INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot) VALUES(?,?,N'Kiểm tra xe',0)", booking, service);
            reject(c, Set.of(547), "INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot) VALUES(?,-1,N'Không tồn tại',0)", booking);
            reject(c, Set.of(547), "UPDATE dbo.booking_services SET reference_price_snapshot=-1 WHERE booking_id=?", booking);
        });
    }

    @Test
    void checksRejectInvalidEnumsPricesAndSchedulingValues() throws Exception {
        inTransaction(c -> {
            for (String assignment : new String[]{"slot_minutes=0", "car_capacity=-1", "motorbike_capacity=-1", "minimum_lead_minutes=0", "maximum_advance_days=0", "id=2", "version=-1", "timezone='UTC'"})
                reject(c, Set.of(547), "UPDATE dbo.garage_settings SET " + assignment);
            reject(c, Set.of(547), "INSERT dbo.working_hours(day_of_week,opens_at,closes_at) VALUES(0,'08:00','12:00')");
            reject(c, Set.of(547), "INSERT dbo.working_hours(day_of_week,opens_at,closes_at) VALUES(7,'12:00','08:00')");
            reject(c, Set.of(2601, 2627), "INSERT dbo.working_hours(day_of_week,opens_at,closes_at) VALUES(1,'08:00','09:00')");
            long slot = slot(c);
            reject(c, Set.of(547), "UPDATE dbo.appointment_slots SET capacity=-1 WHERE id=?", slot);
            reject(c, Set.of(547), "UPDATE dbo.appointment_slots SET ends_at=starts_at WHERE id=?", slot);
            reject(c, Set.of(547), "UPDATE dbo.appointment_slots SET vehicle_type='TRUCK' WHERE id=?", slot);
            reject(c, Set.of(547), "INSERT dbo.services(code,name,vehicle_type,reference_price) VALUES('BAD',N'Dịch vụ','CAR',-1)");
            reject(c, Set.of(547), "INSERT dbo.services(code,name,vehicle_type,reference_price) VALUES('BAD',N'Dịch vụ','TRUCK',0)");
            reject(c, Set.of(547), "INSERT dbo.users(username,password_hash,display_name,role) VALUES(N'bad','hash',N'Nhân viên','CUSTOMER')");
            long customer = customer(c, "+84912345678"), vehicle = vehicle(c, customer);
            assertSqlError(Set.of(547), () -> booking(c, "BK-BADSTATUS", customer, vehicle, slot, "ONLINE", "OPEN", null));
        });
    }

    @Test
    void schedulingUsesConfiguredHorizonAndKeepsOverridesAcrossHolidays() throws Exception {
        inTransaction(c -> {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            var generator = new SlotGenerator(jdbc, Clock.fixed(Instant.parse("2031-01-06T00:00:00Z"), ZoneOffset.UTC));
            LocalDate monday = LocalDate.parse("2031-01-06");
            var pending = assertThrows(SlotGenerator.ScheduleException.class,
                    () -> generator.availability(monday, "MOTORBIKE"));
            assertEquals("SLOT_SCHEDULE_UNAVAILABLE", pending.getMessage());
            execute(c, "UPDATE dbo.garage_settings SET maximum_advance_days=30 WHERE id=1");
            assertEquals(monday.plusDays(30), generator.regenerate());
            var initial = generator.availability(monday, "MOTORBIKE");
            assertEquals(8, initial.size());
            assertEquals("2031-01-06T08:00:00+07:00", initial.getFirst().startsAt());
            assertEquals(2, initial.getFirst().remainingCapacity());
            assertEquals(8, generator.availability(monday.plusDays(30), "MOTORBIKE").size());
            assertEquals(monday.plusDays(30), generator.regenerate());
            assertEquals(initial, generator.availability(monday, "MOTORBIKE"));
            assertEquals("0", scalar(c, "SELECT MAX(version) FROM dbo.appointment_slots"));

            long slot = Long.parseLong(initial.getFirst().id());
            long customer = customer(c, "+84912345678");
            long vehicle = vehicle(c, customer);
            booking(c, "BK-SG-CONFIRMED", customer, vehicle, slot, "ONLINE", "CONFIRMED", null);
            booking(c, "BK-SG-CANCELLED", customer, vehicle, slot, "ONLINE", "CANCELLED", null);
            assertEquals(1, generator.availability(monday, "MOTORBIKE").getFirst().remainingCapacity());
            var full = generator.updateSlot(slot, false, 1, 0);
            assertEquals(7, generator.availability(monday, "MOTORBIKE").size());
            var edit = generator.updateSlot(slot, true, 3, full.version());
            assertEquals(3, edit.capacity());
            assertTrue(edit.closed());
            assertEquals(7, generator.availability(monday, "MOTORBIKE").size());

            var holiday = generator.addClosedDate(monday, "Nghỉ kiểm thử", 0);
            assertTrue(generator.availability(monday, "MOTORBIKE").isEmpty());
            generator.removeClosedDate(Long.parseLong(holiday.id()), 1);
            var restored = generator.adminSlots(monday, "MOTORBIKE").getFirst();
            assertEquals(Long.toString(slot), restored.id());
            assertTrue(restored.closed());
            assertEquals(3, restored.capacityOverride());
            assertEquals(1, restored.used());
            assertEquals(7, generator.availability(monday, "MOTORBIKE").size());

            var beforeChange = generator.scheduleSettings().settings();
            generator.updateSettings(beforeChange,
                    List.of(new Hours(1, LocalTime.of(20, 0), LocalTime.of(23, 0))), beforeChange.version());
            var evening = generator.availability(monday, "MOTORBIKE");
            assertEquals(3, evening.size());
            assertEquals("2031-01-06T23:00:00+07:00", evening.getLast().endsAt());
            assertEquals(restored.id(), generator.adminSlots(monday, "MOTORBIKE").getFirst().id());
        });
    }

    @Test
    void serviceCatalogPagesAndMapsDatabaseConflicts() throws Exception {
        inTransaction(c -> {
            var catalog = new ServiceCatalog(new JdbcTemplate(new SingleConnectionDataSource(c, true)));
            var first = catalog.create("first", "Kiểm tra xe", null, "BOTH", 0);
            catalog.create("second", "Thay dầu", null, "CAR", 120_000);
            var page = catalog.adminList(1, 1);
            assertEquals(2, page.totalElements());
            assertEquals(2, page.totalPages());
            assertEquals(first, page.items().getFirst());
            assertEquals(2, catalog.publicList("CAR").size());
            assertEquals(1, catalog.publicList("MOTORBIKE").size());
            var updated = catalog.update(Long.parseLong(first.id()), first.name(), null, "BOTH", null, false, 0);
            assertEquals(1, updated.version());
            assertTrue(catalog.publicList("MOTORBIKE").isEmpty());
            assertThrows(ServiceCatalog.NotFound.class,
                    () -> catalog.update(-1, "Không tồn tại", null, "BOTH", 0L, true, 0));
            assertThrows(ServiceCatalog.DuplicateCode.class,
                    () -> catalog.create(" FIRST ", "Trùng mã", null, "BOTH", 0));
        });
    }

    @Test
    void v004EnforcesDefaultsAndBounds() throws Exception {
        inTransaction(c -> {
            long slot = slot(c);
            assertEquals("0:none", scalar(c, """
                    SELECT CONCAT(manual_closed,':',COALESCE(CONVERT(varchar(10),capacity_override),'none'))
                    FROM dbo.appointment_slots WHERE id=?
                    """, slot));
            reject(c, Set.of(547), "UPDATE dbo.appointment_slots SET capacity_override=-1 WHERE id=?", slot);
            reject(c, Set.of(547), "UPDATE dbo.appointment_slots SET capacity_override=101 WHERE id=?", slot);
            long service = id(c, """
                    INSERT dbo.services(code,name,vehicle_type,reference_price)
                    OUTPUT INSERTED.id VALUES('IT-V004',N'Dịch vụ','CAR',0)
                    """);
            assertEquals("0", scalar(c, "SELECT version FROM dbo.services WHERE id=?", service));
            reject(c, Set.of(547), "UPDATE dbo.services SET version=-1 WHERE id=?", service);
        });
    }

    @Test
    void idempotencyEnforcesJsonHashExpiryAndUniqueKey() throws Exception {
        inTransaction(c -> {
            insertResult(c, "it-scope", "key");
            assertSqlError(Set.of(2601, 2627), () -> insertResult(c, "it-scope", "key"));
            insertResult(c, "it-scope", "KEY");
            reject(c, Set.of(547), "UPDATE dbo.idempotency_records SET response_json=N'not json' WHERE scope='it-scope'");
            reject(c, Set.of(547), "UPDATE dbo.idempotency_records SET request_hash='short' WHERE scope='it-scope'");
            reject(c, Set.of(547), "UPDATE dbo.idempotency_records SET http_status=999 WHERE scope='it-scope'");
            reject(c, Set.of(547), "UPDATE dbo.idempotency_records SET expires_at=created_at WHERE scope='it-scope'");
        });
    }

    @Test
    void failedBookingTransactionRollsBackNewCustomerAndVehicle() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            long customer = customer(c, "+84912345678"), vehicle = vehicle(c, customer);
            assertSqlError(Set.of(547), () -> booking(c, "BK-ROLLBACK", customer, vehicle, -1L, "ONLINE", "CONFIRMED", null));
            c.rollback();
        }
        try (Connection c = connect()) {
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM dbo.customers"));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM dbo.vehicles"));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM dbo.bookings"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(20)
    void sameIdempotencyKeyWaitsForCommitOrRollback(boolean rollbackFirst) throws Exception {
        String scope = "it-" + UUID.randomUUID();
        try (Connection first = connect(); Connection second = connect(); var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            assertNull(lockedResult(first, scope));
            CountDownLatch started = new CountDownLatch(1);
            var waiting = pool.submit(() -> {
                started.countDown();
                return lockedResult(second, scope);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(waiting.isDone(), "Second request should wait for the missing-key lock");
            if (rollbackFirst) {
                first.rollback();
                assertNull(waiting.get(8, TimeUnit.SECONDS));
                insertResult(second, scope, "same-key");
                second.commit();
            } else {
                insertResult(first, scope, "same-key");
                first.commit();
                assertEquals("{}", waiting.get(8, TimeUnit.SECONDS));
                second.commit();
            }
        } finally {
            try (Connection c = connect()) {
                execute(c, "DELETE FROM dbo.idempotency_records WHERE scope=?", scope);
            }
        }
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, credentials);
        try (Statement s = c.createStatement()) { s.execute(CONNECTION_SETTINGS); }
        catch (SQLException e) { c.close(); throw e; }
        return c;
    }

    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name FROM sys.tables WHERE schema_id=SCHEMA_ID('dbo')")) {
            while (rs.next()) result.add(rs.getString(1));
        }
        return result;
    }

    private static long customer(Connection c, String phone) throws SQLException {
        return id(c, "INSERT dbo.customers(full_name,phone_normalized) OUTPUT INSERTED.id VALUES(N'Khách kiểm thử',?)", phone);
    }

    private static long vehicle(Connection c, long customer) throws SQLException {
        return id(c, "INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized) OUTPUT INSERTED.id VALUES(?,'MOTORBIKE',N'59X-123.45',N'59X12345')", customer);
    }

    private static long slot(Connection c) throws SQLException {
        return id(c, "INSERT dbo.appointment_slots(starts_at,ends_at,vehicle_type,capacity) OUTPUT INSERTED.id VALUES('2030-01-01T01:00:00+00:00','2030-01-01T02:00:00+00:00','MOTORBIKE',2)");
    }

    private static long booking(Connection c, String code, long customer, long vehicle, Long slot, String source, String status, Long staff) throws SQLException {
        return id(c, """
                INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                    contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                OUTPUT INSERTED.id VALUES(?,?,?,?,?,?,'MOTORBIKE',N'Khách kiểm thử','+84912345678',N'59X-123.45',N'59X12345',?)
                """, code, customer, vehicle, slot, source, status, staff);
    }

    private static void insertResult(Connection c, String scope, String key) throws SQLException {
        execute(c, """
                INSERT dbo.idempotency_records(scope,request_key,request_hash,http_status,response_json,expires_at)
                VALUES(?,?,?,201,N'{}',DATEADD(HOUR,24,TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')))
                """, scope, key, HASH);
    }

    private static String lockedResult(Connection c, String scope) throws SQLException {
        return scalar(c, "SELECT response_json FROM dbo.idempotency_records WITH (UPDLOCK,HOLDLOCK) WHERE scope=? AND request_key='same-key'", scope);
    }

    private static long id(Connection c, String sql, Object... params) throws SQLException {
        return Long.parseLong(scalar(c, sql, params));
    }

    private static String scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = statement(c, sql, params); ResultSet rs = s.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = statement(c, sql, params)) { s.executeUpdate(); }
    }

    private static PreparedStatement statement(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
        return s;
    }

    private static void reject(Connection c, Set<Integer> codes, String sql, Object... params) {
        assertSqlError(codes, () -> execute(c, sql, params));
    }

    private static void assertSqlError(Set<Integer> codes, SqlAction action) {
        SQLException e = assertThrows(SQLException.class, action::run);
        assertTrue(codes.contains(e.getErrorCode()), "Expected SQL constraint error, got code " + e.getErrorCode());
    }

    private static void inTransaction(SqlConsumer action) throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try { action.accept(c); } finally { c.rollback(); }
        }
    }

    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    @FunctionalInterface private interface SqlConsumer { void accept(Connection c) throws Exception; }
}
