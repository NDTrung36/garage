package vn.garage.identity.infrastructure;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GarageUserDetailsService implements UserDetailsService {
    private final UserJpaRepository users;
    public GarageUserDetailsService(UserJpaRepository users) { this.users = users; }

    @Override @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        String normalized = username.trim().toLowerCase(java.util.Locale.ROOT);
        UserEntity user = users.findByUsername(normalized).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        if (!user.isActive()) throw new UsernameNotFoundException("Invalid credentials");
        return new GaragePrincipal(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getAuthVersion(), user.getPasswordHash());
    }
}
