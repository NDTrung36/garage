package vn.garage.identity.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidityFilter extends OncePerRequestFilter {
    private final SessionValidityService service;
    public SessionValidityFilter(SessionValidityService service) { this.service = service; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
        String path = request.getServletPath();
        boolean protectedEndpoint = path.startsWith("/api/staff/") || path.startsWith("/api/admin/") || path.equals("/api/auth/me");
        if (protectedEndpoint && auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof GaragePrincipal principal && !service.valid(principal)) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"SESSION_REVOKED\",\"message\":\"Phiên đăng nhập đã hết hiệu lực.\"}");
            return;
        }
        chain.doFilter(request, response);
        }
        catch (org.springframework.dao.DataAccessException ex) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Dịch vụ xác thực tạm thời không khả dụng.\"}");
        }
    }
}
