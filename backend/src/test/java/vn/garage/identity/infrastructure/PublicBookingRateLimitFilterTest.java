package vn.garage.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicBookingRateLimitFilterTest {
    @Test void limitsBookingRequestsAndProvidesRetryAfter() throws Exception {
        var filter = new PublicBookingRateLimitFilter(Set.of(),
                Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper());
        AtomicInteger passed = new AtomicInteger();
        for (int i = 0; i < 11; i++) {
            var request = request("/api/public/bookings", "192.0.2.10");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
            if (i == 10) {
                assertEquals(429, response.getStatus());
                assertEquals("600", response.getHeader("Retry-After"));
                assertTrue(response.getContentAsString().contains("PUBLIC_RATE_LIMITED"));
            }
        }
        assertEquals(10, passed.get());
    }

    @Test void doesNotTrustForwardedIpFromAnUntrustedProxy() throws Exception {
        var filter = new PublicBookingRateLimitFilter(Set.of("127.0.0.1"), Clock.systemUTC(), new ObjectMapper());
        int lastStatus = 0;
        for (int i = 0; i < 31; i++) {
            var request = request("/api/public/lookups", "198.51.100.5");
            request.addHeader("X-Real-IP", "203.0.113.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            lastStatus = response.getStatus();
        }
        assertEquals(429, lastStatus);
    }

    @Test void capsTotalNumberOfClientBuckets() throws Exception {
        var filter = new PublicBookingRateLimitFilter(Set.of(), Clock.systemUTC(), new ObjectMapper());
        AtomicInteger passed = new AtomicInteger();
        int lastStatus = 0;
        for (int i = 0; i <= 4096; i++) {
            var request = request("/api/public/lookups", "198.51." + (i / 256) + "." + (i % 256));
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
            lastStatus = response.getStatus();
        }
        assertEquals(4096, passed.get());
        assertEquals(429, lastStatus);
    }

    private static MockHttpServletRequest request(String path, String ip) {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path); request.setRemoteAddr(ip);
        return request;
    }
}
