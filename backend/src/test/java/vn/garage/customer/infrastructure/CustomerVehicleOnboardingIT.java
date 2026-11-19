package vn.garage.customer.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.customer.domain.ContactAndVehicle;

class CustomerVehicleOnboardingIT {
    private static String url;
    private static Properties credentials;

    @BeforeAll
    static void migrateDedicatedDatabase() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to a dedicated SQL Server migration test database");
        credentials = new Properties();
        String username = System.getenv("MIGRATION_TEST_USERNAME");
        String password = System.getenv("MIGRATION_TEST_PASSWORD");
        if (username != null && !username.isBlank()) credentials.setProperty("user", username);
        if (password != null && !password.isBlank()) credentials.setProperty("password", password);
        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DB_NAME()")) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).matches("garage_migration_test(?:_[a-z0-9]+)?"),
                        "Refusing to use a non-test database");
            }
        }
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @Test
    void reusesExistingCustomerAndVehicleWithoutOverwritingTheirProfile() throws Exception {
        String phone = phone();
        String plate = plate();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var onboarding = new CustomerVehicleOnboarding(new JdbcCustomerVehicleStore(db));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        try {
            var first = transactions.execute(status -> onboarding.obtain(contact("Nguyễn An", phone, "CAR", plate,
                    "Toyota", "Vios")));
            var reused = transactions.execute(status -> onboarding.obtain(contact("Tên mới", phone, "CAR", plate,
                    "Hãng mới", "Mẫu mới")));

            assertEquals(first.customerId(), reused.customerId());
            assertEquals(first.vehicleId(), reused.vehicleId());
            assertEquals("Tên mới", reused.snapshot().fullName());
            assertEquals("Hãng mới", reused.snapshot().brand());
            assertEquals("Nguyễn An", db.queryForObject("SELECT full_name FROM dbo.customers WHERE id=?",
                    String.class, first.customerId()));
            assertEquals("Toyota", db.queryForObject("SELECT brand FROM dbo.vehicles WHERE id=?",
                    String.class, first.vehicleId()));
        } finally {
            cleanup(db, phone);
        }
    }

    @Test
    void allowsSamePlateForDifferentCustomersButRejectsTypeChangeForSameCustomer() throws Exception {
        String plate = plate();
        String firstPhone = phone();
        String secondPhone = phone();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var onboarding = new CustomerVehicleOnboarding(new JdbcCustomerVehicleStore(db));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        try {
            var first = transactions.execute(status -> onboarding.obtain(contact("Khách Một", firstPhone,
                    "CAR", plate, null, null)));
            var otherCustomer = transactions.execute(status -> onboarding.obtain(contact("Khách Hai", secondPhone,
                    "CAR", plate, null, null)));
            assertNotEquals(first.customerId(), otherCustomer.customerId());
            assertNotEquals(first.vehicleId(), otherCustomer.vehicleId());
            assertEquals(first.snapshot().plateNormalized(), otherCustomer.snapshot().plateNormalized());

            var changedType = contact("Tên sửa", firstPhone, "MOTORBIKE", plate, null, null);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> transactions.execute(status -> onboarding.obtain(changedType)));
            assertEquals("VEHICLE_TYPE_CONFLICT", error.getMessage());
        } finally {
            cleanup(db, firstPhone);
            cleanup(db, secondPhone);
        }
    }

    @Test
    void concurrentRequestsForSamePhoneAndPlateCreateOneCustomerAndVehicle() throws Exception {
        String phone = phone();
        String plate = plate();
        try (var pool = pool()) {
            var db = new JdbcTemplate(pool);
            var onboarding = new CustomerVehicleOnboarding(new JdbcCustomerVehicleStore(db));
            var firstEntered = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            var contact = contact("Khách race", phone, "CAR", plate, null, null);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(pool)).execute(status -> {
                    var result = onboarding.obtain(contact);
                    firstEntered.countDown();
                    await(releaseFirst);
                    return result;
                }));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                var second = workers.submit(() -> {
                    secondStarted.countDown();
                    return new TransactionTemplate(new DataSourceTransactionManager(pool))
                            .execute(status -> onboarding.obtain(contact));
                });
                assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
                Thread.sleep(250);
                assertFalse(second.isDone(), "Second transaction must wait for the unique phone key range");
                releaseFirst.countDown();
                var firstResult = first.get(8, TimeUnit.SECONDS);
                var secondResult = second.get(8, TimeUnit.SECONDS);
                assertEquals(firstResult.customerId(), secondResult.customerId());
                assertEquals(firstResult.vehicleId(), secondResult.vehicleId());
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.customers WHERE phone_normalized=?",
                        Integer.class, phone));
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.vehicles WHERE customer_id=? AND plate_normalized=?",
                        Integer.class, firstResult.customerId(), firstResult.snapshot().plateNormalized()));
            } finally {
                releaseFirst.countDown();
                cleanup(db, phone);
            }
        }
    }

    @Test
    void callerRollbackRemovesNewCustomerAndVehicleTogether() throws Exception {
        String phone = phone();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var onboarding = new CustomerVehicleOnboarding(new JdbcCustomerVehicleStore(db));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        try {
            assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
                onboarding.obtain(contact("Rollback Test", phone, "CAR", plate(), null, null));
                throw new IllegalStateException("caller failed after onboarding");
            }));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.customers WHERE phone_normalized=?",
                    Integer.class, phone));
            assertEquals(0, db.queryForObject("""
                    SELECT COUNT(*) FROM dbo.vehicles v JOIN dbo.customers c ON c.id=v.customer_id
                    WHERE c.phone_normalized=?
                    """, Integer.class, phone));
        } finally {
            cleanup(db, phone);
        }
    }

    private static ContactAndVehicle contact(String name, String phone, String type, String plate,
            String brand, String model) {
        return ContactAndVehicle.from(name, phone, type, plate, brand, model);
    }

    private static String phone() {
        int suffix = Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000);
        return "+849" + String.format(java.util.Locale.ROOT, "%08d", suffix);
    }

    private static String plate() { return "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(); }

    private static void cleanup(JdbcTemplate db, String phone) {
        db.update("DELETE FROM dbo.vehicles WHERE customer_id IN (SELECT id FROM dbo.customers WHERE phone_normalized=?)", phone);
        db.update("DELETE FROM dbo.customers WHERE phone_normalized=?", phone);
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setConnectionProperties(credentials);
        return source;
    }

    private static HikariDataSource pool() {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        if (credentials.getProperty("user") != null) config.setUsername(credentials.getProperty("user"));
        if (credentials.getProperty("password") != null) config.setPassword(credentials.getProperty("password"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    private static Connection connect() throws Exception { return DriverManager.getConnection(url, credentials); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
