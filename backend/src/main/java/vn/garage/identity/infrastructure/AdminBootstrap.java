package vn.garage.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.identity.application.UserManagementService;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final boolean enabled;
    private final String username, displayName, password;
    private final UserJpaRepository users;
    private final PasswordEncoder encoder;
    public AdminBootstrap(@Value("${garage.identity.bootstrap.enabled:false}") boolean enabled,
        @Value("${garage.identity.bootstrap.username:}") String username,
        @Value("${garage.identity.bootstrap.display-name:}") String displayName,
        @Value("${garage.identity.bootstrap.password:}") String password,
        UserJpaRepository users, PasswordEncoder encoder) {
        this.enabled=enabled; this.username=username; this.displayName=displayName; this.password=password; this.users=users; this.encoder=encoder;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        String normalized = UserManagementService.normalizeUsername(username);
        if (displayName.isBlank() || displayName.length()>100 || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalStateException("Invalid bootstrap admin configuration");
        if (users.lockAndCountUsers() != 0) return;
        users.saveAndFlush(UserEntity.create(normalized, encoder.encode(password), displayName.trim(), "ADMIN"));
    }
}
