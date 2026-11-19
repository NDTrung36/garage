package vn.garage.repair.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.repair.domain.BookingSlotRepository;
import vn.garage.repair.domain.GuestBookingRequest;
import vn.garage.support.idempotency.IdempotencyExecutor;

class GuestBookingServiceTest {
    private static final String KEY = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void delegatesReplayToSharedExecutorWithoutRecheckingBooking() {
        var request = request();
        String hash = IdempotencyExecutor.hash(new ObjectMapper(), request);
        var idem = mock(IdempotencyExecutor.class);
        var result = new GuestBookingService.BookingResult("BK-23456789ABCD", "CONFIRMED",
                "2031-01-06T09:00:00+07:00", "2031-01-06T10:00:00+07:00", "CAR",
                List.of(new GuestBookingService.BookedService("Thay dầu", 120_000)), 120_000);
        doReturn(new IdempotencyExecutor.Result<>(201, result, true)).when(idem)
                .executeWithHash(eq("public.booking.create"), eq(KEY), eq(hash),
                        eq(GuestBookingService.BookingResult.class), any());
        var slots = mock(BookingSlotRepository.class);
        var catalog = mock(BookingServiceCatalog.class);
        var customers = mock(CustomerVehicleOnboarding.class);

        var actual = service(idem, slots, catalog, customers).create(request, KEY);

        assertEquals(result, actual);
        verifyNoInteractions(slots, catalog, customers);
        verify(idem).executeWithHash(eq("public.booking.create"), eq(KEY), eq(hash),
                eq(GuestBookingService.BookingResult.class), any());
    }

    @Test
    void rejectsReuseOfKeyForDifferentPayloadThroughSharedConflict() {
        var idem = mock(IdempotencyExecutor.class);
        when(idem.executeWithHash(eq("public.booking.create"), eq(KEY), anyString(),
                eq(GuestBookingService.BookingResult.class), any()))
                .thenThrow(new IdempotencyExecutor.IdempotencyConflictException());

        assertThrows(GuestBookingService.IdempotencyConflictException.class,
                () -> service(idem, mock(BookingSlotRepository.class), mock(BookingServiceCatalog.class),
                        mock(CustomerVehicleOnboarding.class)).create(request(), KEY));
    }

    private static GuestBookingService service(IdempotencyExecutor idempotency, BookingSlotRepository slots,
            BookingServiceCatalog catalog, CustomerVehicleOnboarding customers) {
        return new GuestBookingService(mock(JdbcTemplate.class), idempotency, slots, catalog, customers,
                Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC));
    }

    private static GuestBookingRequest request() {
        return GuestBookingRequest.create("Nguyễn Văn An", "0912345678", "CAR", "30A-12345",
                null, null, List.of(1L), 77, null);
    }
}
