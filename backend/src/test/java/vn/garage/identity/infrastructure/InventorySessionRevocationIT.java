package vn.garage.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.garage.repair.infrastructure.ScheduleJobs;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_URL", matches = "jdbc:sqlserver://.+")
class InventorySessionRevocationIT {
    private static final String URL = System.getenv("MIGRATION_TEST_URL");
    private static final String USERNAME = System.getenv("MIGRATION_TEST_USERNAME");
    private static final String PASSWORD = System.getenv("MIGRATION_TEST_PASSWORD");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ScheduleJobs scheduleJobs;
    private final java.util.List<String> usernames = new java.util.ArrayList<>();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        assertDedicatedDatabase();
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.datasource.username", () -> USERNAME == null ? "" : USERNAME);
        properties.add("spring.datasource.password", () -> PASSWORD == null ? "" : PASSWORD);
    }

    @Test void roleChangeAndAccountLockRevokeBothLiveSessionsOnNextInternalRequest() throws Exception {
        long adminId = createUser("ADMIN");
        GaragePrincipal admin = new GaragePrincipal(adminId, usernames.get(0), "Test admin", "ADMIN", 0, "test-hash");
        MockHttpSession adminSession1 = session(admin);
        MockHttpSession adminSession2 = session(admin);
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(adminSession1)).andExpect(status().isOk());
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(adminSession2)).andExpect(status().isOk());

        jdbc.update("UPDATE dbo.users SET role='STAFF', auth_version=auth_version+1, updated_at=SYSUTCDATETIME() WHERE id=?", adminId);
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(adminSession1)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(adminSession2)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        long staffId = createUser("STAFF");
        GaragePrincipal staff = new GaragePrincipal(staffId, usernames.get(1), "Test staff", "STAFF", 0, "test-hash");
        MockHttpSession staffSession1 = session(staff);
        MockHttpSession staffSession2 = session(staff);
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(staffSession1)).andExpect(status().isOk());
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(staffSession2)).andExpect(status().isOk());
        jdbc.update("UPDATE dbo.users SET active=0, auth_version=auth_version+1, updated_at=SYSUTCDATETIME() WHERE id=?", staffId);
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(staffSession1)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(get("/api/staff/parts").servletPath("/api/staff/parts").session(staffSession2)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @AfterEach void cleanup() {
        for (String username : usernames) jdbc.update("DELETE FROM dbo.users WHERE username=?", username);
        usernames.clear();
    }

    private long createUser(String role) {
        String username = "inv07-" + UUID.randomUUID().toString().replace("-", "");
        usernames.add(username);
        jdbc.update("INSERT INTO dbo.users(username,password_hash,display_name,role,active,auth_version) VALUES(?,?,?, ?,1,0)",
                username, "test-hash", "Người dùng kiểm thử", role);
        return jdbc.queryForObject("SELECT id FROM dbo.users WHERE username=?", Long.class, username);
    }

    private static MockHttpSession session(GaragePrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, principal.passwordHash(), principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static void assertDedicatedDatabase() {
        if (URL == null || !URL.startsWith("jdbc:sqlserver://")) throw new IllegalStateException("MIGRATION_TEST_URL must point to the dedicated SQL Server test database");
        Properties credentials = new Properties();
        if (USERNAME != null && !USERNAME.isBlank()) credentials.setProperty("user", USERNAME);
        if (PASSWORD != null && !PASSWORD.isBlank()) credentials.setProperty("password", PASSWORD);
        try (Connection connection = DriverManager.getConnection(URL, credentials);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DB_NAME()")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).matches("garage_migration_test(?:_[a-z0-9]+)?"),
                    "Refusing to run session revocation test outside dedicated migration database");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot verify dedicated SQL Server test database", failure);
        }
    }
}
