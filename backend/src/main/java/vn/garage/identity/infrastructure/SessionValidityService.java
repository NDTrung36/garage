package vn.garage.identity.infrastructure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionValidityService {
    private final UserJpaRepository users;
    public SessionValidityService(UserJpaRepository users) { this.users = users; }
    @Transactional(readOnly = true)
    public boolean valid(GaragePrincipal principal) {
        return users.findById(principal.id()).filter(u -> u.isActive()
                && u.getAuthVersion() == principal.authVersion()
                && u.getRole().equals(principal.role())).isPresent();
    }
}
