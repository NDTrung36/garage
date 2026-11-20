package vn.garage.repair.api;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.application.RepairPartQuery;
import vn.garage.support.idempotency.IdempotencyExecutor;

@WebMvcTest(RepairPartsController.class)
@Import({SecurityConfiguration.class, RepairErrors.class, RepairPartsSecurityTest.ClockConfig.class})
class RepairPartsSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean RepairPartCommands commands;
    @MockitoBean RepairPartQuery query;
    @MockitoBean SessionValidityService validity;

    @BeforeEach void validPrincipals() { when(validity.valid(any())).thenReturn(true); }

    @Test void guestCannotReadRepairPartDetails() throws Exception {
        mvc.perform(get("/api/staff/repair-orders/9/parts")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(query, commands);
    }

    @Test void staffCanReadAndOperateRepairPartRoutes() throws Exception {
        var detail = new RepairPartQuery.Detail("9", "IN_PROGRESS", 3, List.of(),
                List.of(new RepairPartQuery.IssueView("30", "21", "17", "1.000", "0.500",
                        OffsetDateTime.parse("2031-01-06T01:00:00Z"))), 0, 101, 101);
        when(query.detail(9)).thenReturn(detail);
        var response = new RepairPartCommands.CommandResponse(null, "0.000",
                new RepairPartCommands.PartItemView("21", "17", "Lọc nhớt", "cái", "1.000", 101, 101), 4, 101);
        when(commands.issue(9, 17, "1.000", "issue-1", 41, "STAFF"))
                .thenReturn(new IdempotencyExecutor.Result<>(201, response, false));
        when(commands.returnPart(9, 30, "0.500", "return-1", 41, "STAFF"))
                .thenReturn(new IdempotencyExecutor.Result<>(201, response, false));
        when(commands.updatePrice(9, 21, 101, "điều chỉnh giá", 3, 41, "STAFF"))
                .thenReturn(new RepairPartCommands.PriceResponse(response.partItem(), 4, 101));

        mvc.perform(get("/api/staff/repair-orders/9/parts").session(session(staff())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.repairOrderId").value("9"))
                .andExpect(jsonPath("$.returnableIssues[0].id").value("30"))
                .andExpect(jsonPath("$.returnableIssues[0].remainingQuantity").value("0.500"));
        mvc.perform(post("/api/staff/repair-orders/9/part-issues").session(session(staff())).with(csrf())
                .header("Idempotency-Key", "issue-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"partId\":\"17\",\"quantity\":\"1.000\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.partItem.id").value("21"))
                .andExpect(jsonPath("$.partItem.quantity").value("1.000"));
        mvc.perform(post("/api/staff/repair-orders/9/part-returns").session(session(staff())).with(csrf())
                .header("Idempotency-Key", "return-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalIssueId\":\"30\",\"quantity\":\"0.500\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/staff/repair-orders/9/part-items/21/price").session(session(staff())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPrice\":101,\"reason\":\"điều chỉnh giá\",\"expectedVersion\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partItem.unitPrice").value(101));
        verify(query).detail(9);
        verify(commands).issue(9, 17, "1.000", "issue-1", 41, "STAFF");
        verify(commands).returnPart(9, 30, "0.500", "return-1", 41, "STAFF");
        verify(commands).updatePrice(9, 21, 101, "điều chỉnh giá", 3, 41, "STAFF");
    }

    @Test void csrfBlocksIssueReturnAndPriceBeforeUseCases() throws Exception {
        mvc.perform(post("/api/staff/repair-orders/9/part-issues").session(session(staff()))
                .header("Idempotency-Key", "issue-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"partId\":\"17\",\"quantity\":\"1.000\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/staff/repair-orders/9/part-returns").session(session(staff()))
                .header("Idempotency-Key", "return-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalIssueId\":\"30\",\"quantity\":\"0.500\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(patch("/api/staff/repair-orders/9/part-items/21/price").session(session(staff()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPrice\":101,\"reason\":\"điều chỉnh giá\",\"expectedVersion\":3}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(commands, query);
    }

    private static MockHttpSession session(GaragePrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, principal.passwordHash(), principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
    private static GaragePrincipal staff() { return new GaragePrincipal(41, "staff", "Nhân viên", "STAFF", 0, "hash"); }

    @TestConfiguration static class ClockConfig {
        @Bean Clock testClock() { return Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC); }
    }
}
