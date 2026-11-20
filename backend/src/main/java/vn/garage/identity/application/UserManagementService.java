package vn.garage.identity.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.identity.infrastructure.*;

@Service
public class UserManagementService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserManagementService.class);
    private final UserJpaRepository users;
    private final PasswordEncoder passwords;
    public UserManagementService(UserJpaRepository users, PasswordEncoder passwords) { this.users = users; this.passwords = passwords; }
    public record UserView(String id, String username, String displayName, String role, boolean active, long authVersion) { }
    public record UserPage(java.util.List<UserView> items, int page, int size, long total) { }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_PAGE");
        Page<UserEntity> result = users.findAllByOrderByIdAsc(PageRequest.of(page, size));
        return new UserPage(result.getContent().stream().map(this::view).toList(), page, size, result.getTotalElements());
    }

    @Transactional
    public UserView create(long actorId, String username, String displayName, String role, String password) {
        String normalized = normalizeUsername(username);
        validatePassword(password);
        validateRole(role);
        UserEntity actor=users.findById(actorId).orElseThrow();
        if (!actor.isActive() || !actor.getRole().equals("ADMIN")) throw new IllegalStateException("FORBIDDEN");
        if (users.existsByUsername(normalized)) throw new IllegalStateException("USERNAME_EXISTS");
        UserView created;
        try {
            created = view(users.saveAndFlush(UserEntity.create(normalized, passwords.encode(password), displayName.trim(), role)));
        } catch (DataIntegrityViolationException conflict) {
            if (isUsernameConflict(conflict)) throw new IllegalStateException("USERNAME_EXISTS", conflict);
            throw conflict;
        }
        audit(actorId, Long.parseLong(created.id()), "USER_CREATED"); return created;
    }

    public record Update(String displayName, String role, Boolean active, Long expectedAuthVersion) { }
    @Transactional
    public UserView update(long actorId, long targetId, Update command) {
        lockUsers();
        UserEntity actor = users.findById(actorId).orElseThrow(UserNotFoundException::new);
        UserEntity target = users.findById(targetId).orElseThrow(UserNotFoundException::new);
        if (!actor.isActive() || !actor.getRole().equals("ADMIN")) throw new IllegalStateException("FORBIDDEN");
        if (command.displayName()==null && command.role()==null && command.active()==null) throw new IllegalArgumentException("EMPTY_UPDATE");
        if (command.expectedAuthVersion() == null || target.getAuthVersion() != command.expectedAuthVersion()) throw new IllegalStateException("STALE_AUTH_VERSION");
        if (actorId == targetId && (Boolean.FALSE.equals(command.active()) || "STAFF".equals(command.role()))) throw new IllegalStateException("SELF_ACCOUNT_CHANGE_FORBIDDEN");
        boolean wasActiveAdmin = target.isActive() && target.getRole().equals("ADMIN");
        long activeAdminCount = wasActiveAdmin ? users.countByActiveTrueAndRole("ADMIN") : 0;
        if (command.displayName() != null) target.setDisplayName(command.displayName().trim());
        boolean securityChange = (command.role() != null && !command.role().equals(target.getRole())) || (command.active() != null && command.active() != target.isActive());
        if (command.role() != null) { validateRole(command.role()); target.setRole(command.role()); }
        if (command.active() != null) target.setActive(command.active());
        if (wasActiveAdmin && (!target.isActive() || !"ADMIN".equals(target.getRole())) && activeAdminCount <= 1) throw new IllegalStateException("LAST_ACTIVE_ADMIN");
        if (securityChange) target.setAuthVersion(target.getAuthVersion() + 1);
        target.touch(); UserView updated=view(users.saveAndFlush(target)); audit(actorId,targetId,"USER_UPDATED"); return updated;
    }

    @Transactional
    public void resetPassword(long actorId, long targetId, String password, long expectedVersion) {
        lockUsers(); validatePassword(password);
        UserEntity actor = users.findById(actorId).orElseThrow(UserNotFoundException::new); UserEntity target = users.findById(targetId).orElseThrow(UserNotFoundException::new);
        if (!actor.isActive() || !actor.getRole().equals("ADMIN")) throw new IllegalStateException("FORBIDDEN");
        if (target.getAuthVersion() != expectedVersion) throw new IllegalStateException("STALE_AUTH_VERSION");
        target.setPasswordHash(passwords.encode(password)); target.setAuthVersion(target.getAuthVersion() + 1); target.touch(); users.saveAndFlush(target); audit(actorId,targetId,"PASSWORD_RESET");
    }

    private void lockUsers() { users.lockAndCountUsers(); }
    private static boolean isUsernameConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("UX_users_username")) return true;
        }
        return false;
    }
    public static final class UserNotFoundException extends RuntimeException { }
    private void audit(long actorId,long targetId,String action) {
        log.info("identity_admin_action actorId={} targetId={} action={} outcome=SUCCESS traceId={}",actorId,targetId,action,
            org.slf4j.MDC.get("traceId") == null ? "-" : org.slf4j.MDC.get("traceId"));
    }
    private UserView view(UserEntity u) { return new UserView(Long.toString(u.getId()), u.getUsername(), u.getDisplayName(), u.getRole(), u.isActive(), u.getAuthVersion()); }
    public static String normalizeUsername(String value) {
        if (value == null) throw new IllegalArgumentException("INVALID_USERNAME");
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!v.matches("[a-z0-9._-]{3,100}")) throw new IllegalArgumentException("INVALID_USERNAME"); return v;
    }
    private static void validatePassword(String value) {
        if (value == null || value.length() < 12 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("INVALID_PASSWORD");
    }
    private static void validateRole(String role) { if (!"ADMIN".equals(role) && !"STAFF".equals(role)) throw new IllegalArgumentException("INVALID_ROLE"); }
}
