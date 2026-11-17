package vn.garage.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import vn.garage.identity.infrastructure.GaragePrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final HttpSessionCsrfTokenRepository csrfRepository;
    public AuthController(AuthenticationManager authenticationManager, HttpSessionCsrfTokenRepository csrfRepository) { this.authenticationManager = authenticationManager; this.csrfRepository = csrfRepository; }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    public record LoginRequest(@NotBlank @Size(max=100) String username, @NotBlank String password) { }
    public record UserResponse(String id, String username, String displayName, String role) { }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            throw new BadCredentialsException("INVALID_CREDENTIALS");
        } catch (org.springframework.security.authentication.AuthenticationServiceException ex) {
            throw ex;
        }
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        csrfRepository.saveToken(null, request, null);
        return response(auth);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(auth));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrfRepository.saveToken(null, request, response);
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        response.addHeader("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        return ResponseEntity.noContent().build();
    }

    private UserResponse response(Authentication auth) {
        var principal = auth.getPrincipal();
        if (principal instanceof GaragePrincipal p) return new UserResponse(Long.toString(p.id()), p.username(), p.displayName(), p.role());
        throw new IllegalStateException("Unexpected authenticated principal");
    }
}
