package vn.garage.identity.infrastructure;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfiguration {
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception { return config.getAuthenticationManager(); }
    @Bean HttpSessionCsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository(); repository.setHeaderName("X-CSRF-TOKEN"); return repository;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, SessionValidityService validity, HttpSessionCsrfTokenRepository csrfRepo) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(exact("POST", "/api/public/bookings"), exact("POST", "/api/public/lookups")))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation(fix -> fix.changeSessionId()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "UNAUTHENTICATED", "Cần đăng nhập để tiếp tục."))
                .accessDeniedHandler((req, res, ex) -> writeError(res, 403,
                    ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                    ex instanceof org.springframework.security.web.csrf.CsrfException ? "Yêu cầu bảo mật không hợp lệ. Vui lòng tải lại trang." : "Không có quyền thực hiện thao tác.")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers("/api/auth/me").authenticated()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/staff/**").hasAnyRole("ADMIN", "STAFF")
                .anyRequest().denyAll())
            .addFilterAfter(new SessionValidityFilter(validity), AnonymousAuthenticationFilter.class);
        http.addFilterAfter(new LoginRateLimitFilter(), CsrfFilter.class);
        return http.build();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
    private static RequestMatcher exact(String method, String path) {
        return request -> method.equals(request.getMethod()) && path.equals(request.getServletPath());
    }
}
