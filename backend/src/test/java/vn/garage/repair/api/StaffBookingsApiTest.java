package vn.garage.repair.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.repair.application.StaffBookingQuery;
import vn.garage.repair.application.VehicleIntakeService;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.support.idempotency.IdempotencyExecutor;

@WebMvcTest(StaffBookingsController.class)
@Import({SecurityConfiguration.class, RepairErrors.class, StaffBookingsApiTest.ClockConfig.class})
class StaffBookingsApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean StaffBookingQuery query;
    @MockitoBean VehicleIntakeService intake;
    @MockitoBean SessionValidityService validity;

    @org.junit.jupiter.api.BeforeEach void validSession() { when(validity.valid(any())).thenReturn(true); }

    @Test
    void listIsStaffOnlyLocalDateAwareAndNeverCached() throws Exception {
        when(query.list(LocalDate.of(2031, 1, 6), "CONFIRMED", 0, 20))
                .thenReturn(new BookingStore.BookingPage(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/staff/bookings").with(user(staff()))
                        .param("date", "2031-01-06").param("status", "CONFIRMED").with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items").isEmpty());
        verify(query).list(LocalDate.of(2031, 1, 6), "CONFIRMED", 0, 20);
        mvc.perform(get("/api/staff/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void checkInUsesPrincipalActorAndRequiresCsrf() throws Exception {
        var result = new VehicleIntakeService.CheckInResult("7", "BK-7", BookingStore.BookingStatus.CHECKED_IN,
                3, "12", RepairStatus.IN_PROGRESS, 0, OffsetDateTime.parse("2031-01-06T01:00:00Z"));
        when(intake.checkIn(7, 2, 12345, "Tình trạng", null, 41)).thenReturn(result);
        mvc.perform(post("/api/staff/bookings/7/check-in").with(user(staff()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"odometerKm\":12345,\"conditionNotes\":\"Tình trạng\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/staff/bookings/7/check-in").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"odometerKm\":12345,\"conditionNotes\":\"Tình trạng\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.repairOrderId").value("12"))
                .andExpect(jsonPath("$.repairStatus").value("IN_PROGRESS"));
        verify(intake).checkIn(7, 2, 12345, "Tình trạng", null, 41);
    }

    @Test
    void rejectsInvalidIntakeBeforeCallingUseCase() throws Exception {
        mvc.perform(post("/api/staff/bookings/7/check-in").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1,\"odometerKm\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(intake);
    }

    @Test void mapsFeatureStateAndInvariantErrorsWithoutIdentityCodes() throws Exception {
        when(intake.checkIn(7, 2, null, null, null, 41))
                .thenThrow(new VehicleIntakeService.BookingStateConflict());
        mvc.perform(post("/api/staff/bookings/7/check-in").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_STATE_CONFLICT"));
        when(intake.checkIn(8, 2, null, null, null, 41))
                .thenThrow(new VehicleIntakeService.RepairInvariantViolation());
        mvc.perform(post("/api/staff/bookings/8/check-in").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("REPAIR_DATA_INCONSISTENT"));
    }

    @Test void walkInRequiresKeyAndUsesCanonicalInputAndSessionActor() throws Exception {
        var result = new VehicleIntakeService.CheckInResult("9", "BK-23456789ABCD",
                BookingStore.BookingStatus.CHECKED_IN, 0, "15", RepairStatus.IN_PROGRESS, 0,
                OffsetDateTime.parse("2031-01-06T01:00:00Z"));
        when(intake.walkIn(any(), eq("walk-key"), eq(41L)))
                .thenReturn(new IdempotencyExecutor.Result<>(201, result, false));
        String body = """
                {"contact":{"fullName":"Nguyễn Văn An","phone":"0912345678"},
                 "vehicle":{"type":"CAR","plateNumber":"51a-123.45"},
                 "serviceIds":["8","7"],"odometerKm":1000}
                """;
        mvc.perform(post("/api/staff/walk-ins").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/staff/walk-ins").with(user(staff())).with(csrf()).header("Idempotency-Key", "walk-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.bookingId").value("9")).andExpect(jsonPath("$.repairOrderId").value("15"));
        var command = org.mockito.ArgumentCaptor.forClass(VehicleIntakeService.WalkInCommand.class);
        verify(intake).walkIn(command.capture(), eq("walk-key"), eq(41L));
        org.junit.jupiter.api.Assertions.assertEquals("+84912345678", command.getValue().contact().phoneE164());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(7L, 8L), command.getValue().serviceIds());
    }

    @Test void walkInRejectsMissingContactVehicleAndServiceData() throws Exception {
        mvc.perform(post("/api/staff/walk-ins").with(user(staff())).with(csrf()).header("Idempotency-Key", "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(intake);
    }

    private static GaragePrincipal staff() { return new GaragePrincipal(41, "staff", "Nhân viên", "STAFF", 0, "hash"); }

    @TestConfiguration static class ClockConfig {
        @Bean Clock testClock() { return Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC); }
    }
}
