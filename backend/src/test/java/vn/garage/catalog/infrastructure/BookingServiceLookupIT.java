package vn.garage.catalog.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.catalog.application.ServiceCatalog;

class BookingServiceLookupIT {
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
        try (Connection connection = DriverManager.getConnection(url, credentials);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DB_NAME()")) {
            assertTrue(rows.next());
            assertTrue(rows.getString(1).matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to use a non-test database");
        }
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @Test
    void returnsOnlyActiveCompatibleServicesWithCurrentNameAndPriceSnapshot() {
        var db = new JdbcTemplate(dataSource());
        var lookup = new JdbcBookingServiceLookup(db);
        var catalog = new ServiceCatalog(db, lookup);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        long carId = addService(db, "CAR-" + suffix, "Cân chỉnh bánh", "CAR", 120_000, true);
        long motoId = addService(db, "MOTO-" + suffix, "Vệ sinh nồi", "MOTORBIKE", 80_000, true);
        long bothId = addService(db, "BOTH-" + suffix, "Kiểm tra tổng quát", "BOTH", 50_000, true);
        long inactiveId = addService(db, "OFF-" + suffix, "Dịch vụ ngừng", "CAR", 30_000, false);
        try {
            var selected = catalog.activeForBooking(List.of(bothId, carId), "CAR");
            assertEquals(List.of(
                    new BookingServiceCatalog.ServiceSnapshot(carId, "Cân chỉnh bánh", 120_000),
                    new BookingServiceCatalog.ServiceSnapshot(bothId, "Kiểm tra tổng quát", 50_000)), selected);
            assertEquals(List.of(bothId), catalog.activeForBooking(List.of(bothId), "MOTORBIKE")
                    .stream().map(BookingServiceCatalog.ServiceSnapshot::id).toList());
            assertEquals("SERVICE_NOT_AVAILABLE", assertThrows(IllegalArgumentException.class,
                    () -> catalog.activeForBooking(List.of(motoId), "CAR")).getMessage());
            assertEquals("SERVICE_NOT_AVAILABLE", assertThrows(IllegalArgumentException.class,
                    () -> catalog.activeForBooking(List.of(inactiveId), "CAR")).getMessage());
            assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                    () -> catalog.activeForBooking(List.of(carId, carId), "CAR")).getMessage());
            assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                    () -> catalog.activeForBooking(List.of(), "CAR")).getMessage());

            db.update("UPDATE dbo.services SET name=N'Tên mới',reference_price=200000 WHERE id=?", carId);
            assertEquals(new BookingServiceCatalog.ServiceSnapshot(carId, "Tên mới", 200_000),
                    catalog.activeForBooking(List.of(carId), "CAR").getFirst());
        } finally {
            db.update("DELETE FROM dbo.services WHERE id IN (?,?,?,?)", carId, motoId, bothId, inactiveId);
        }
    }

    private static long addService(JdbcTemplate db, String code, String name, String type, long price, boolean active) {
        return db.queryForObject("""
                INSERT dbo.services(code,name,vehicle_type,reference_price,active)
                OUTPUT INSERTED.id VALUES(?,?,?,?,?)
                """, Long.class, code, name, type, price, active);
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setConnectionProperties(credentials);
        return source;
    }
}
