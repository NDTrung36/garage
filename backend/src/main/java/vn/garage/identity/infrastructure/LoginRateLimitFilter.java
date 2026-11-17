package vn.garage.identity.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BUCKETS = 4096;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(req.getMethod()) || !"/api/auth/login".equals(req.getRequestURI())) { chain.doFilter(req,res); return; }
        long now=clock.millis(); String ip=clientIp(req);
        if (attempts.size() >= MAX_BUCKETS && !attempts.containsKey(ip)) {
            attempts.entrySet().removeIf(e -> { synchronized(e.getValue()) { return e.getValue().isEmpty() || now-e.getValue().peekLast()>=60_000; } });
        }
        if (attempts.size() >= MAX_BUCKETS && !attempts.containsKey(ip)) { limited(res); return; }
        ArrayDeque<Long> q=attempts.computeIfAbsent(ip,k->new ArrayDeque<>());
        synchronized(q) {
            while(!q.isEmpty() && now-q.peekFirst()>=60_000) q.removeFirst();
            if(q.size()>=10) { limited(res); return; }
            q.addLast(now);
        }
        chain.doFilter(req,res);
    }
    private String clientIp(HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        String trusted=System.getProperty("garage.identity.trusted-proxies","");
        if (!java.util.Arrays.asList(trusted.split(",")).contains(remote)) return remote;
        String forwarded=request.getHeader("X-Forwarded-For");
        if (forwarded==null || forwarded.isBlank()) return remote;
        String candidate=forwarded.split(",")[0].trim();
        return candidate.matches("[0-9a-fA-F:.]{2,45}") ? candidate : remote;
    }
    private void limited(HttpServletResponse res) throws IOException {
        res.setStatus(429); res.setHeader("Retry-After","60"); res.setContentType("application/json"); res.getWriter().write("{\"code\":\"LOGIN_RATE_LIMITED\",\"message\":\"Vượt giới hạn đăng nhập.\"}");
    }
}
