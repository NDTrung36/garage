package vn.garage.support.idempotency;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.transaction.support.TransactionTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import vn.garage.support.transaction.SqlServerWriteTransactions;
import vn.garage.support.audit.JdbcAuditWriter;

class IdempotencySqlServerIT {
    private static String url;
    private static Properties credentials;

    @BeforeAll
    static void validateDedicatedDatabaseAndMigrate() throws Exception {
        url = System.getenv("MIGRATION_TEST_URL");
        assertNotNull(url, "Set MIGRATION_TEST_URL to the dedicated SQL Server migration test database");
        credentials = new Properties();
        String username = System.getenv("MIGRATION_TEST_USERNAME");
        String password = System.getenv("MIGRATION_TEST_PASSWORD");
        if (username != null && !username.isBlank()) credentials.setProperty("user", username);
        if (password != null && !password.isBlank()) credentials.setProperty("password", password);
        try (Connection c = connect()) {
            assertTrue(scalar(c, "SELECT DB_NAME()").matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to run idempotency tests outside the dedicated migration database");
        }
        org.flywaydb.core.Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .locations("classpath:db/migration").defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sameEmptyKeyWaitsThenReplaysExactlyOneCommittedMutation() throws Exception {
        String useCase = scope();
        String scope = IdempotencyExecutor.scope(useCase, 41L);
        String markerScope = scope + ".mutation";
        String key = key();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var executor = executor(source, db);
        var firstEnteredMutation = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var mutations = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> executor.execute(useCase, 41L, key, command(), Body.class, () -> {
                mutations.incrementAndGet();
                writeMutationMarker(db, markerScope);
                firstEnteredMutation.countDown();
                await(releaseFirst);
                return new IdempotencyExecutor.Success<>(201, new Body("MV-1", "0.000", "1.200"));
            }));
            assertTrue(firstEnteredMutation.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return executor.execute(useCase, 41L, key, command(), Body.class,
                        () -> new IdempotencyExecutor.Success<>(201, new Body("MV-2", "0.000", "1.200")));
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertFalse(second.isDone(), "The second execution should wait on the composite key range lock");
            releaseFirst.countDown();

            var firstResult = first.get(8, TimeUnit.SECONDS);
            var secondResult = second.get(8, TimeUnit.SECONDS);
            assertEquals(firstResult.body(), secondResult.body());
            assertEquals(201, secondResult.httpStatus());
            assertEquals(1, mutations.get());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    Integer.class, scope, key));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=?",
                    Integer.class, markerScope), "The committed domain mutation must happen once");
            assertTrue(firstResult.replayed() ^ secondResult.replayed());
        } finally {
            releaseFirst.countDown();
            deleteScope(scope);
            deleteScope(markerScope);
        }
    }

    @Test
    void rollbackAfterMutationAllowsTheNextRequestToExecute() throws Exception {
        String useCase = scope();
        String scope = IdempotencyExecutor.scope(useCase, 41L);
        String markerScope = scope + ".mutation";
        String key = key();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var executor = executor(source, db);
        var mutations = new AtomicInteger();
        try {
            assertThrows(IllegalStateException.class, () -> executor.execute(useCase, 41L, key, command(), Body.class, () -> {
                mutations.incrementAndGet();
                writeMutationMarker(db, markerScope);
                throw new IllegalStateException("simulated failure after domain mutation");
            }));
            var successful = executor.execute(useCase, 41L, key, command(), Body.class, () -> {
                mutations.incrementAndGet();
                writeMutationMarker(db, markerScope);
                return new IdempotencyExecutor.Success<>(200, new Body("MV-OK", "0.000", "1.200"));
            });
            assertFalse(successful.replayed());
            assertEquals(2, mutations.get());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    Integer.class, scope, key));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=?",
                    Integer.class, markerScope), "The failed attempt's mutation must roll back");
        } finally {
            deleteScope(scope);
            deleteScope(markerScope);
        }
    }

    @Test
    void keyComparisonIsCaseSensitiveOnSqlServer() throws Exception {
        String useCase = scope();
        String scope = IdempotencyExecutor.scope(useCase, 41L);
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var executor = executor(source, db);
        var calls = new AtomicInteger();
        try {
            var first = executor.execute(useCase, 41L, "Case-Key", command(), Body.class, () -> {
                calls.incrementAndGet();
                return new IdempotencyExecutor.Success<>(201, new Body("UPPER", "0", "1"));
            });
            var second = executor.execute(useCase, 41L, "case-key", command(), Body.class, () -> {
                calls.incrementAndGet();
                return new IdempotencyExecutor.Success<>(201, new Body("LOWER", "0", "1"));
            });

            assertFalse(first.replayed());
            assertFalse(second.replayed());
            assertEquals(2, calls.get());
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=?",
                    Integer.class, scope));
        } finally {
            deleteScope(scope);
        }
    }

    @Test
    void auditAndPartVersionRollbackInTheSameWriteTransaction() throws Exception {
        var source = dataSource();
        var db = new JdbcTemplate(source);
        long partId = db.queryForObject("""
                INSERT dbo.parts(code,name,unit,vehicle_type,reference_price)
                OUTPUT INSERTED.id VALUES(?,N'IT part',N'cái','BOTH',100)
                """, Long.class, "IT-AUDIT-" + UUID.randomUUID());
        var writes = new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db);
        var audit = new JdbcAuditWriter(db, new ObjectMapper());
        try {
            assertThrows(IllegalStateException.class, () -> writes.execute(() -> {
                db.update("UPDATE dbo.parts SET reference_price=200,version=version+1 WHERE id=?", partId);
                audit.write(null, "PART_PRICE_CHANGED", "part", partId, "test reason",
                        java.util.Map.of("referencePrice", 100), java.util.Map.of("referencePrice", 200));
                throw new IllegalStateException("rollback after audit");
            }));
            assertEquals(0L, db.queryForObject("SELECT version FROM dbo.parts WHERE id=?", Long.class, partId));
            assertEquals(100L, db.queryForObject("SELECT reference_price FROM dbo.parts WHERE id=?", Long.class, partId));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.audit_logs WHERE entity_type='part' AND entity_id=?",
                    Integer.class, partId));
        } finally {
            db.update("DELETE FROM dbo.parts WHERE id=?", partId);
        }
    }

    @Test
    @Timeout(value = 18, unit = TimeUnit.SECONDS)
    void lockTimeoutReturnsRetryableExceptionAndRestoresPooledSessionSetting() throws Exception {
        String useCase = scope();
        String scope = IdempotencyExecutor.scope(useCase, 41L);
        String key = key();
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(credentials.getProperty("user"));
        config.setPassword(credentials.getProperty("password"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionInitSql("SET LOCK_TIMEOUT 37");
        try (var pool = new HikariDataSource(config)) {
            try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                s.execute("SET LOCK_TIMEOUT 37");
            }
            var db = new JdbcTemplate(pool);
            var store = new JdbcIdempotencyStore(db);
            var lock = new TransactionTemplate(new DataSourceTransactionManager(pool));
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var threads = Executors.newSingleThreadExecutor()) {
                var owner = threads.submit(() -> lock.execute(status -> {
                    store.lock(scope, key, OffsetDateTime.now(ZoneOffset.UTC));
                    locked.countDown();
                    await(release);
                    return null;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var executor = executor(pool, db);
                assertThrows(IdempotencyExecutor.RequestInProgressException.class,
                        () -> executor.execute(useCase, 41L, key, command(), Body.class,
                                () -> new IdempotencyExecutor.Success<>(200, new Body("unused", "0", "1"))));
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            try (Connection c = pool.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT @@LOCK_TIMEOUT")) {
                assertTrue(rs.next());
                assertEquals(37, rs.getInt(1), "LOCK_TIMEOUT must be restored before Hikari reuses the connection");
            }
        }
    }

    @Test
    void expiryReplacementAndCleanupUseTheSharedTable() throws Exception {
        String useCase = scope();
        String scope = IdempotencyExecutor.scope(useCase, 41L);
        String key = key();
        var source = dataSource();
        var db = new JdbcTemplate(source);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                INSERT dbo.idempotency_records(scope,request_key,request_hash,http_status,response_json,created_at,expires_at)
                VALUES(?,?,?,200,N'{"old":true}',?,?)
                """)) {
            ps.setString(1, scope); ps.setString(2, key); ps.setString(3, "0".repeat(64));
            ps.setObject(4, now.minusHours(25)); ps.setObject(5, now.minusHours(1)); ps.executeUpdate();
        }
        try {
            var fixedClock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
            var executor = executor(source, db, fixedClock);
            var result = executor.execute(useCase, 41L, key, command(), Body.class,
                    () -> new IdempotencyExecutor.Success<>(200, new Body("NEW", "0", "1")));
            assertEquals("NEW", result.body().movementId());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=? AND request_hash=?",
                    Integer.class, scope, key, IdempotencyExecutor.hash(new com.fasterxml.jackson.databind.ObjectMapper(), command())));
            assertEquals(86400, db.queryForObject("SELECT DATEDIFF(SECOND,created_at,expires_at) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    Integer.class, scope, key));

            String cleanupKey = key();
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                    INSERT dbo.idempotency_records(scope,request_key,request_hash,http_status,response_json,created_at,expires_at)
                    VALUES(?,?,?,200,N'{"expired":true}',?,?)
                    """)) {
                ps.setString(1, scope); ps.setString(2, cleanupKey); ps.setString(3, "1".repeat(64));
                ps.setObject(4, now.minusHours(25)); ps.setObject(5, now.minusHours(1)); ps.executeUpdate();
            }
            var job = new IdempotencyCleanupJob(new JdbcIdempotencyStore(db), fixedClock);
            assertTrue(job.cleanExpiredBatch() >= 1);
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dbo.idempotency_records WHERE scope=? AND request_key=?",
                    Integer.class, scope, cleanupKey));
        } finally {
            deleteScope(scope);
        }
    }

    @Test
    void compositePrimaryKeyLookupPlanUsesIndexSeek() throws Exception {
        String scope = IdempotencyExecutor.scope(scope(), 41L);
        String key = key();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("SET SHOWPLAN_XML ON");
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT request_hash,http_status,response_json,expires_at
                    FROM dbo.idempotency_records WITH (UPDLOCK,HOLDLOCK,INDEX(PK_idempotency_records))
                    WHERE scope=? AND request_key=?
                    """)) {
                ps.setObject(1, scope, Types.VARCHAR);
                ps.setObject(2, key, Types.VARCHAR);
                try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String plan = rs.getString(1);
                assertTrue(plan.contains("Index Seek"), plan);
                assertTrue(plan.contains("PK_idempotency_records"), plan);
                }
            } finally {
                s.execute("SET SHOWPLAN_XML OFF");
            }
        }
    }

    @Test
    void applicationWriteConnectionHasRequiredSettingsAndEnforcesFilteredPartIndex() {
        try (var pool = writePool(applicationWriteConnectionSettings(), 2)) {
            var db = new JdbcTemplate(pool);
            var settings = db.queryForList("""
                    SELECT SESSIONPROPERTY('ANSI_NULLS') ansi_nulls,
                           SESSIONPROPERTY('ANSI_PADDING') ansi_padding,
                           SESSIONPROPERTY('ANSI_WARNINGS') ansi_warnings,
                           SESSIONPROPERTY('ARITHABORT') arithabort,
                           SESSIONPROPERTY('CONCAT_NULL_YIELDS_NULL') concat_null_yields_null,
                           SESSIONPROPERTY('QUOTED_IDENTIFIER') quoted_identifier,
                           SESSIONPROPERTY('NUMERIC_ROUNDABORT') numeric_roundabort,
                           @@LOCK_TIMEOUT lock_timeout
                    """).getFirst();
            assertEquals(1, ((Number) settings.get("ansi_nulls")).intValue());
            assertEquals(1, ((Number) settings.get("ansi_padding")).intValue());
            assertEquals(1, ((Number) settings.get("ansi_warnings")).intValue());
            assertEquals(1, ((Number) settings.get("arithabort")).intValue());
            assertEquals(1, ((Number) settings.get("concat_null_yields_null")).intValue());
            assertEquals(1, ((Number) settings.get("quoted_identifier")).intValue());
            assertEquals(0, ((Number) settings.get("numeric_roundabort")).intValue());
            assertEquals(5_000, ((Number) settings.get("lock_timeout")).intValue());

            String suffix = UUID.randomUUID().toString().replace("-", "");
            String phone = "+849" + String.format(java.util.Locale.ROOT, "%08d",
                    Integer.parseInt(suffix.substring(0, 7), 16) % 100_000_000);
            long userId = db.queryForObject("""
                    INSERT dbo.users(username,password_hash,display_name,role)
                    OUTPUT INSERTED.id VALUES(?, 'test-hash', N'SC03 Test', 'STAFF')
                    """, Long.class, "sc03-" + suffix);
            long customerId = db.queryForObject("""
                    INSERT dbo.customers(full_name,phone_normalized)
                    OUTPUT INSERTED.id VALUES(N'SC03 Test', ?)
                    """, Long.class, phone);
            long vehicleId = db.queryForObject("""
                    INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized)
                    OUTPUT INSERTED.id VALUES(?,'CAR',N'51A12345',N'51A12345')
                    """, Long.class, customerId);
            long bookingId = db.queryForObject("""
                    INSERT dbo.bookings(code,customer_id,vehicle_id,source,status,vehicle_type_snapshot,
                        contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,created_by)
                    OUTPUT INSERTED.id VALUES(?,?,?,'WALK_IN','CHECKED_IN','CAR',N'SC03 Test',?,N'51A12345',N'51A12345',?)
                    """, Long.class, "SC3-" + suffix.substring(0, 16), customerId, vehicleId, phone, userId);
            long orderId = db.queryForObject("""
                    INSERT dbo.repair_orders(booking_id,received_by)
                    OUTPUT INSERTED.id VALUES(?,?)
                    """, Long.class, bookingId, userId);
            long partId = db.queryForObject("""
                    INSERT dbo.parts(code,name,unit,vehicle_type,reference_price)
                    OUTPUT INSERTED.id VALUES(?,N'SC03 test part',N'cái','CAR',0)
                    """, Long.class, "SC03-" + suffix);
            try {
                db.update("""
                        INSERT dbo.repair_items(repair_order_id,item_type,part_id,description,unit_snapshot,quantity,unit_price)
                        VALUES(?,'PART',?,N'SC03 test part',N'cái',1.000,0)
                        """, orderId, partId);
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("""
                        INSERT dbo.repair_items(repair_order_id,item_type,part_id,description,unit_snapshot,quantity,unit_price)
                        VALUES(?,'PART',?,N'SC03 duplicate part',N'cái',1.000,0)
                        """, orderId, partId));
            } finally {
                db.update("DELETE FROM dbo.repair_items WHERE repair_order_id=?", orderId);
                db.update("DELETE FROM dbo.repair_orders WHERE id=?", orderId);
                db.update("DELETE FROM dbo.bookings WHERE id=?", bookingId);
                db.update("DELETE FROM dbo.vehicles WHERE id=?", vehicleId);
                db.update("DELETE FROM dbo.customers WHERE id=?", customerId);
                db.update("DELETE FROM dbo.parts WHERE id=?", partId);
                db.update("DELETE FROM dbo.users WHERE id=?", userId);
            }
        }
    }

    private static HikariDataSource writePool(String initSql, int maxPoolSize) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        if (credentials.getProperty("user") != null) config.setUsername(credentials.getProperty("user"));
        if (credentials.getProperty("password") != null) config.setPassword(credentials.getProperty("password"));
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionInitSql(initSql);
        return new HikariDataSource(config);
    }

    private static String applicationWriteConnectionSettings() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        return properties.getProperty("spring.datasource.hikari.connection-init-sql");
    }

    private static IdempotencyExecutor executor(javax.sql.DataSource source, JdbcTemplate db) {
        return executor(source, db, Clock.systemUTC());
    }

    private static IdempotencyExecutor executor(javax.sql.DataSource source, JdbcTemplate db, Clock clock) {
        return new IdempotencyExecutor(new JdbcIdempotencyStore(db),
                new SqlServerWriteTransactions(new DataSourceTransactionManager(source), db),
                new com.fasterxml.jackson.databind.ObjectMapper(), clock);
    }

    private static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setConnectionProperties(credentials);
        return source;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(url, credentials);
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void deleteScope(String scope) throws Exception {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM dbo.idempotency_records WHERE scope=?")) {
            ps.setString(1, scope);
            ps.executeUpdate();
        }
    }

    private static void writeMutationMarker(JdbcTemplate db, String scope) {
        db.update("""
                INSERT dbo.idempotency_records(scope,request_key,request_hash,http_status,response_json,created_at,expires_at)
                VALUES(?, 'mutation', ?, 200, N'{"written":true}',
                    TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00'),
                    DATEADD(HOUR,24,TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')))
                """, scope, "a".repeat(64));
    }

    private static String scope() { return "it." + UUID.randomUUID().toString().replace("-", ""); }
    private static String key() { return "IT-" + UUID.randomUUID().toString().replace("-", ""); }
    private static Object command() { return java.util.Map.of("partId", 7L, "quantity", new java.math.BigDecimal("1.200")); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    public record Body(String movementId, String before, String after) { }
}
