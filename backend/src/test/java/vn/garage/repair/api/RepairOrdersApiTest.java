package vn.garage.repair.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import vn.garage.repair.application.RepairOrderQuery;
import vn.garage.repair.application.RepairOrderService;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;

@WebMvcTest(RepairOrdersController.class)
@Import({SecurityConfiguration.class, RepairErrors.class, RepairOrdersApiTest.ClockConfig.class})
class RepairOrdersApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean RepairOrderQuery query;
    @MockitoBean RepairOrderService commands;
    @MockitoBean vn.garage.repair.application.RepairCompletionService completion;
    @MockitoBean SessionValidityService validity;

    @org.junit.jupiter.api.BeforeEach void validSession() { when(validity.valid(any())).thenReturn(true); }

    @Test void listAndDetailAreStaffOnlyNoStoreReads() throws Exception {
        when(query.list("IN_PROGRESS", 0, 20)).thenReturn(new RepairOrderQuery.Page(List.of(), 0, 20, 0, 0));
        when(query.detail(7)).thenReturn(detail());
        mvc.perform(get("/api/staff/repair-orders").with(user(staff())).param("status", "IN_PROGRESS"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/staff/repair-orders/7").with(user(staff())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.requestedServices[0].name").value("Thay dầu"))
                .andExpect(jsonPath("$.laborItems").isEmpty()).andExpect(jsonPath("$.totals.totalAmount").value(0));
        mvc.perform(get("/api/staff/repair-orders/7")).andExpect(status().isUnauthorized());
    }

    @Test void headerPatchPreservesOmittedAndExplicitNullAndRequiresCsrf() throws Exception {
        when(query.detail(7)).thenReturn(detail());
        mvc.perform(patch("/api/staff/repair-orders/7").with(user(staff()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"conditionNotes\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/staff/repair-orders/7").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"conditionNotes\":null}"))
                .andExpect(status().isOk());
        var patchCaptor = org.mockito.ArgumentCaptor.forClass(RepairOrderService.HeaderPatch.class);
        verify(commands).patchHeader(eq(7L), eq(2L), patchCaptor.capture(), eq(41L), eq("STAFF"));
        org.junit.jupiter.api.Assertions.assertFalse(patchCaptor.getValue().odometerPresent());
        org.junit.jupiter.api.Assertions.assertTrue(patchCaptor.getValue().conditionNotesPresent());
        org.junit.jupiter.api.Assertions.assertNull(patchCaptor.getValue().conditionNotes());
        org.junit.jupiter.api.Assertions.assertFalse(patchCaptor.getValue().internalNotesPresent());
        mvc.perform(patch("/api/staff/repair-orders/7").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"unknown\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test void laborCommandsUsePrincipalVersionAndReturnCalculatedTotals() throws Exception {
        when(commands.addLabor(7, 8L, "Thay dầu", "0.500", 101L, 2, 41, "STAFF"))
                .thenReturn(new RepairOrderService.LaborResult("9", "7", 3,
                        new RepairTotals.Totals(new BigDecimal("51"), BigDecimal.ZERO, new BigDecimal("51"))));
        mvc.perform(post("/api/staff/repair-orders/7/labor-items").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedVersion":2,"serviceId":"8","description":"Thay dầu","quantity":"0.500","unitPrice":101}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.itemId").value("9"))
                .andExpect(jsonPath("$.totals.laborAmount").value(51));
        verify(commands).addLabor(7, 8L, "Thay dầu", "0.500", 101L, 2, 41, "STAFF");
    }

    @Test void validationAndStaleVersionMapToFeatureErrors() throws Exception {
        mvc.perform(post("/api/staff/repair-orders/7/labor-items").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"description\":\"x\",\"quantity\":\"0.0001\",\"unitPrice\":0}"))
                .andExpect(status().isBadRequest());
        when(commands.patchHeader(eq(7L), eq(2L), any(), eq(41L), eq("STAFF")))
                .thenThrow(new RepairOrderService.StaleVersion());
        mvc.perform(patch("/api/staff/repair-orders/7").with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"odometerKm\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test void completionUsesSessionActorAndRequiresCsrfAndIntegerVersion() throws Exception {
        String url = "/api/staff/repair-orders/7/complete";
        var at = OffsetDateTime.parse("2031-01-06T01:00:00Z");
        var invoice = new vn.garage.billing.application.InvoiceIssuance.Summary("9", "INV-123456789ABC", 51, "UNPAID", at);
        when(completion.complete(7, 2, 41, "STAFF")).thenReturn(
                new vn.garage.repair.application.RepairCompletionService.Result("7", "6",
                        RepairStatus.COMPLETED, BookingStore.BookingStatus.COMPLETED, 3, at, invoice));
        mvc.perform(post(url).with(user(staff())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}")).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(staff())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2.5}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user(staff())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.invoice.totalAmount").value(51))
                .andExpect(jsonPath("$.invoice.paymentStatus").value("UNPAID"));
        verify(completion).complete(7, 2, 41, "STAFF");
    }

    private static RepairOrderQuery.Detail detail() {
        return new RepairOrderQuery.Detail("7", "6", "BK-123", "ONLINE", BookingStore.BookingStatus.CHECKED_IN,
                RepairStatus.IN_PROGRESS, 2, null, null, null, "41", OffsetDateTime.parse("2031-01-06T01:00:00Z"),
                null, null, "CAR", "Khách test", "+84912345678", "51A12345", null, null,
                List.of(new BookingStore.RequestedService("8", "Thay dầu", 100000)), List.of(), List.of(), List.of(),
                new RepairOrderQuery.Totals(0, 0, 0), null);
    }

    private static GaragePrincipal staff() { return new GaragePrincipal(41, "staff", "Nhân viên", "STAFF", 0, "hash"); }

    @TestConfiguration static class ClockConfig {
        @Bean Clock testClock() { return Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC); }
    }
}
