package vn.garage.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class PublicBookingRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BUCKETS = 4096;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Object bucketAdmissionLock = new Object();
    private final Set<String> trustedProxies;
    private final Clock clock;
    private final ObjectMapper json;

    public PublicBookingRateLimitFilter(Set<String> trustedProxies, Clock clock, ObjectMapper json) {
        this.trustedProxies = Set.copyOf(trustedProxies); this.clock = clock; this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (!"POST".equals(request.getMethod()) || !path.equals("/api/public/bookings") && !path.equals("/api/public/lookups")) {
            chain.doFilter(request, response); return;
        }
        long window = path.endsWith("/bookings") ? 600_000 : 60_000;
        int limit = path.endsWith("/bookings") ? 10 : 30;
        long now = clock.millis();
        String key = path + "|" + clientIp(request);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            synchronized (bucketAdmissionLock) {
                bucket = buckets.get(key);
                if (bucket == null) {
                    if (buckets.size() >= MAX_BUCKETS) {
                        buckets.entrySet().removeIf(entry -> {
                            Bucket value = entry.getValue();
                            synchronized (value) { return now - value.startedAt >= value.windowMillis; }
                        });
                    }
                    if (buckets.size() >= MAX_BUCKETS) { limited(response, 60); return; }
                    bucket = new Bucket(now, window);
                    buckets.put(key, bucket);
                }
            }
        }
        synchronized (bucket) {
            if (now - bucket.startedAt >= bucket.windowMillis) {
                bucket.startedAt = now; bucket.count = 0;
            }
            if (bucket.count >= limit) {
                long retryAfter = Math.max(1, (bucket.startedAt + bucket.windowMillis - now + 999) / 1000);
                limited(response, retryAfter); return;
            }
            bucket.count++;
            response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(limit - bucket.count));
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!trustedProxies.contains(remote)) return remote;
        String forwarded = request.getHeader("X-Real-IP");
        return forwarded != null && forwarded.matches("[0-9a-fA-F:.]{2,45}") ? forwarded : remote;
    }

    private void limited(HttpServletResponse response, long seconds) throws IOException {
        response.setStatus(429); response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), Map.of("code", "PUBLIC_RATE_LIMITED", "message", "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau."));
    }

    private static final class Bucket {
        private long startedAt;
        private final long windowMillis;
        private int count;
        private Bucket(long startedAt, long windowMillis) { this.startedAt = startedAt; this.windowMillis = windowMillis; }
    }
}
