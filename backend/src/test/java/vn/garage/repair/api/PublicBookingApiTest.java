package vn.garage.repair.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.repair.application.GuestBookingService;
import vn.garage.repair.application.PublicBookingLookup;
import vn.garage.repair.application.SlotGenerator;
import vn.garage.repair.domain.GuestBookingRequest;

@WebMvcTest({PublicBookingController.class, PublicSlotsController.class})
@Import({SecurityConfiguration.class, PublicBookingApiTest.ClockConfig.class})
class PublicBookingApiTest {
    private static final String BOOKING_KEY = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired MockMvc mvc;
    @MockitoBean GuestBookingService bookings;
    @MockitoBean PublicBookingLookup lookups;
    @MockitoBean SlotGenerator slots;
    @MockitoBean SessionValidityService validity;

    @Test @WithAnonymousUser void guestCanBookWithoutLoginOrCsrfAndGetsOnlyPublicResult() throws Exception {
        when(bookings.create(any(GuestBookingRequest.class), eq(BOOKING_KEY)))
                .thenReturn(new GuestBookingService.BookingResult("BK-23456789ABCD", "CONFIRMED",
                        "2031-01-06T09:00:00+07:00", "2031-01-06T10:00:00+07:00", "MOTORBIKE",
                        List.of(new GuestBookingService.BookedService("Thay dầu", 120_000)), 120_000));
        mvc.perform(validBooking().header("Idempotency-Key", BOOKING_KEY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.bookingCode").value("BK-23456789ABCD"))
                .andExpect(jsonPath("$.referenceTotal").value(120000)).andExpect(jsonPath("$.customerId").doesNotExist());
    }

    @Test @WithAnonymousUser void rejectsMalformedKeysBeforeCreatingBooking() throws Exception {
        for (String key : List.of("abc", "1-1-1-1-1", BOOKING_KEY + "0")) {
            mvc.perform(validBooking().header("Idempotency-Key", key))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }
        mvc.perform(validBooking()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(bookings);
    }

    @Test @WithAnonymousUser void transactionOpenFailureReturns503ForBothPublicCommands() throws Exception {
        var failure = new CannotCreateTransactionException("Cannot open transaction",
                new SQLTransientConnectionException("Connection pool timeout"));
        when(bookings.create(any(GuestBookingRequest.class), eq(BOOKING_KEY))).thenThrow(failure);
        when(lookups.lookup("0912345678", null, 0, 20)).thenThrow(failure);

        mvc.perform(validBooking().header("Idempotency-Key", BOOKING_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(post("/api/public/lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0912345678\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test @WithAnonymousUser void slotScheduleUnavailableKeepsItsRetryAfterHeader() throws Exception {
        LocalDate date = LocalDate.of(2031, 1, 6);
        when(slots.availability(date, "CAR"))
                .thenThrow(new SlotGenerator.ScheduleException(503, "SLOT_SCHEDULE_UNAVAILABLE"));

        mvc.perform(get("/api/public/slots").param("date", date.toString()).param("vehicleType", "CAR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("SLOT_SCHEDULE_UNAVAILABLE"));
    }

    @Test @WithAnonymousUser void lookupIsGuestAccessibleAndNeverCached() throws Exception {
        when(lookups.lookup("0912345678", null, 0, 20)).thenReturn(
                new PublicBookingLookup.LookupPage(List.of(), 0, 20, 0, 0));
        mvc.perform(post("/api/public/lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0912345678\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test @WithAnonymousUser void staffRouteStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/staff/bookings")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder validBooking() {
        return post("/api/public/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"contact":{"fullName":"Nguyễn Văn An","phone":"0912345678"},
                 "vehicle":{"type":"MOTORBIKE","plateNumber":"59A1-123.45"},
                 "serviceIds":["7"],"slotId":"2"}
                """);
    }

    @TestConfiguration static class ClockConfig {
        @Bean Clock testClock() { return Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC); }
    }
}
