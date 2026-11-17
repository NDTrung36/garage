package vn.garage.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.garage.identity.application.UserManagementService;
import vn.garage.identity.infrastructure.GaragePrincipal;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {
    private final UserManagementService users;
    public AdminUsersController(UserManagementService users) { this.users = users; }
    public record CreateRequest(@NotBlank @Size(min=3,max=100) String username, @NotBlank @Size(max=100) String displayName,
        @Pattern(regexp="ADMIN|STAFF") String role, @NotBlank String password) { }
    public record UpdateRequest(@Size(max=100) @Pattern(regexp=".*\\S.*") String displayName, @Pattern(regexp="ADMIN|STAFF") String role,
        Boolean active, @NotNull Long expectedAuthVersion) { }
    public record ResetRequest(@NotBlank String password, @NotNull Long expectedAuthVersion) { }
    @GetMapping public UserManagementService.UserPage list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return users.list(page,size); }
    @PostMapping public ResponseEntity<?> create(@Valid @RequestBody CreateRequest body, Authentication auth) {
        return ResponseEntity.status(201).body(users.create(((GaragePrincipal)auth.getPrincipal()).id(), body.username(), body.displayName(), body.role(), body.password()));
    }
    @PatchMapping("/{id}") public UserManagementService.UserView update(@PathVariable long id, @Valid @RequestBody UpdateRequest body, Authentication auth) {
        var p = (GaragePrincipal) auth.getPrincipal();
        return users.update(p.id(), id, new UserManagementService.Update(body.displayName(),body.role(),body.active(),body.expectedAuthVersion()));
    }
    @PostMapping("/{id}/reset-password") public ResponseEntity<Void> reset(@PathVariable long id, @Valid @RequestBody ResetRequest body, Authentication auth) {
        users.resetPassword(((GaragePrincipal)auth.getPrincipal()).id(),id,body.password(),body.expectedAuthVersion()); return ResponseEntity.noContent().build();
    }
}
