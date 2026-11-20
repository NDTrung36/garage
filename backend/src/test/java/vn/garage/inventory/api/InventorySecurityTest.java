package vn.garage.inventory.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.inventory.application.PartCatalog;
import vn.garage.inventory.application.StockCommands;
import vn.garage.inventory.domain.PartPage;
import vn.garage.inventory.domain.PartView;
import vn.garage.repair.api.RepairErrors;
import vn.garage.repair.api.StaffStockMovementsController;
import vn.garage.repair.application.StockHistoryQuery;
import vn.garage.inventory.application.StockHistory;
import vn.garage.support.idempotency.IdempotencyExecutor;

@WebMvcTest({PartController.class, AdminStockController.class, StaffStockMovementsController.class})
@Import({SecurityConfiguration.class, InventoryErrors.class, RepairErrors.class, InventorySecurityTest.ClockConfig.class})
class InventorySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean PartCatalog catalog;
    @MockitoBean StockCommands commands;
    @MockitoBean StockHistoryQuery history;
    @MockitoBean SessionValidityService validity;

    @BeforeEach void validPrincipals() { when(validity.valid(any())).thenReturn(true); }

    @Test void guestCannotReadInternalCatalogOrLedgerAndThereIsNoPublicLedgerOrAuditRoute() throws Exception {
        mvc.perform(get("/api/staff/parts")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/admin/parts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/stock-movements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/stock-movements")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/audit-logs")).andExpect(status().isNotFound());
        verifyNoInteractions(catalog, commands, history);
    }

    @Test void staffCanReadButCannotCallAdminCommandsEvenWithCsrf() throws Exception {
        when(catalog.list(anyInt(), anyInt(), any(), any(), any())).thenReturn(new PartPage(List.of(), 0, 20, 0, 0));
        when(history.list(0, 20, null, null)).thenReturn(new StockHistory.Page(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/staff/parts").session(session(staff()))).andExpect(status().isOk());
        mvc.perform(get("/api/staff/stock-movements").session(session(staff()))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/parts").session(session(staff()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/parts").session(session(staff())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"A1\",\"name\":\"Lọc\",\"unit\":\"cái\",\"vehicleType\":\"BOTH\",\"referencePrice\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stock-receipts").session(session(staff())).with(csrf()).header("Idempotency-Key", "r1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"1\",\"quantity\":\"1.000\",\"reason\":\"nhập\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stock-adjustments").session(session(staff())).with(csrf()).header("Idempotency-Key", "a1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"1\",\"quantityDelta\":\"1.000\",\"reason\":\"điều chỉnh\"}"))
                .andExpect(status().isForbidden());
        verify(catalog, times(1)).list(anyInt(), anyInt(), any(), any(), any());
        verifyNoMoreInteractions(catalog);
        verify(history).list(0, 20, null, null);
        verifyNoInteractions(commands);
    }

    @Test void adminReadsAndWritesWithPrincipalActorAndStringIds() throws Exception {
        PartView part = new PartView("17", "LOC-17", "Lọc nhớt", "cái", "BOTH", 0, "0.000", true, 0,
                OffsetDateTime.parse("2031-01-06T01:00:00Z"), OffsetDateTime.parse("2031-01-06T01:00:00Z"));
        when(catalog.list(anyInt(), anyInt(), any(), any(), any())).thenReturn(new PartPage(List.of(part), 0, 20, 1, 1));
        when(history.list(0, 20, null, null)).thenReturn(new StockHistory.Page(List.of(), 0, 20, 0, 0));
        when(catalog.create("LOC-17", "Lọc nhớt", "cái", "BOTH", 0, "ADMIN", 7)).thenReturn(part);
        when(catalog.update(17, "Lọc", null, null, null, null, 0L, "ADMIN", 7)).thenReturn(part);
        when(commands.receipt(17, "1.250", "nhập hàng", null, "receipt-1", 7, "ADMIN"))
                .thenReturn(new IdempotencyExecutor.Result<>(201, new StockCommands.CommandResponse(null, "1.250"), false));
        when(commands.adjust(17, "-0.250", "kiểm kê", "adjust-1", 7, "ADMIN"))
                .thenReturn(new IdempotencyExecutor.Result<>(201, new StockCommands.CommandResponse(null, "0.750"), false));
        var admin = session(admin());
        mvc.perform(get("/api/admin/parts").session(admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value("17"))
                .andExpect(jsonPath("$.items[0].stockQuantity").value("0.000"))
                .andExpect(jsonPath("$.items[0].referencePrice").value(0));
        mvc.perform(get("/api/staff/parts").session(session(admin()))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/staff/stock-movements").session(session(admin()))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/admin/parts").session(session(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"LOC-17\",\"name\":\"Lọc nhớt\",\"unit\":\"cái\",\"vehicleType\":\"BOTH\",\"referencePrice\":0}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(patch("/api/admin/parts/17").session(session(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lọc\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/admin/stock-receipts").session(session(admin())).with(csrf()).header("Idempotency-Key", "receipt-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"17\",\"quantity\":\"1.250\",\"reason\":\"nhập hàng\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.stockQuantity").value("1.250"));
        mvc.perform(post("/api/admin/stock-adjustments").session(session(admin())).with(csrf()).header("Idempotency-Key", "adjust-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"17\",\"quantityDelta\":\"-0.250\",\"reason\":\"kiểm kê\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stockQuantity").value("0.750"));
        verify(catalog).create("LOC-17", "Lọc nhớt", "cái", "BOTH", 0, "ADMIN", 7);
        verify(catalog).update(17, "Lọc", null, null, null, null, 0L, "ADMIN", 7);
        verify(commands).receipt(17, "1.250", "nhập hàng", null, "receipt-1", 7, "ADMIN");
        verify(commands).adjust(17, "-0.250", "kiểm kê", "adjust-1", 7, "ADMIN");
    }

    @Test void writesRequireCsrfAndRejectedRequestsDoNotReachUseCases() throws Exception {
        var adminSession = session(admin());
        mvc.perform(post("/api/admin/parts").session(adminSession).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"A1\",\"name\":\"Lọc\",\"unit\":\"cái\",\"vehicleType\":\"BOTH\",\"referencePrice\":10}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(patch("/api/admin/parts/1").session(session(admin())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/admin/stock-receipts").session(session(admin())).header("Idempotency-Key", "r")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"1\",\"quantity\":\"1.000\",\"reason\":\"nhập\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/admin/stock-adjustments").session(session(admin())).header("Idempotency-Key", "a")
                .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"1\",\"quantityDelta\":\"1.000\",\"reason\":\"kiểm kê\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(catalog, commands);
    }

    private static MockHttpSession session(GaragePrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, principal.passwordHash(), principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
    private static GaragePrincipal staff() { return new GaragePrincipal(41, "staff", "Nhân viên", "STAFF", 0, "hash"); }
    private static GaragePrincipal admin() { return new GaragePrincipal(7, "admin", "Quản trị", "ADMIN", 0, "hash"); }

    @TestConfiguration static class ClockConfig {
        @Bean Clock testClock() { return Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC); }
    }
}
