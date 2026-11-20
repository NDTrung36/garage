package vn.garage.billing.api;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.billing.application.InvoiceQuery;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.identity.infrastructure.UserEntity;
import vn.garage.identity.infrastructure.UserJpaRepository;
import vn.garage.support.idempotency.IdempotencyExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({StaffInvoicesController.class, AdminInvoicePaymentsController.class})
@Import({SecurityConfiguration.class, SessionValidityService.class, InvoiceRequests.class, InvoiceSecurityTest.ClockConfig.class})
class InvoiceSecurityTest {
    private static final String LIST = "/api/staff/invoices";
    private static final String DETAIL = LIST + "/7";
    private static final String PAY = DETAIL + "/mark-paid";
    private static final String RESET = "/api/admin/invoices/7/reset-payment";
    private static final String PAY_BODY = "{\"expectedVersion\":0,\"paymentMethod\":\"CASH\"}";
    private static final String RESET_BODY = "{\"expectedVersion\":1,\"reason\":\"Nhập nhầm\"}";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-19T08:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean InvoiceQuery query;
    @MockitoBean InvoicePaymentCommands commands;
    @MockitoBean UserJpaRepository users;

    @Test void guestsCannotUseAnyOfTheFourRoutes() throws Exception {
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mvc.perform(get(DETAIL)).andExpect(status().isUnauthorized());
        mvc.perform(command(PAY, PAY_BODY).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        mvc.perform(command(RESET, RESET_BODY).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        verifyNoInteractions(query, commands);
    }

    @Test void staffCanReadAndRecordPaymentButCannotReset() throws Exception {
        var staff = session(41, "STAFF", 0);
        when(query.list(null, 0, 20)).thenReturn(new InvoiceQuery.Page(List.of(), 0, 20, 0, 0));
        when(query.detail(7)).thenReturn(new InvoiceQuery.Detail("7", "INV-123456789ABC", "19", AT, 51,
                "UNPAID", 0, null, null, null, null, null, List.of()));
        when(commands.markPaid(7, 0, "CASH", "try-1", 41, "STAFF")).thenReturn(new IdempotencyExecutor.Result<>(
                200, new InvoicePaymentCommands.PaymentResponse("7", "PAID", 51L, "CASH", "41", AT, 1), false));
        mvc.perform(get(LIST).servletPath(LIST).session(staff)).andExpect(status().isOk());
        mvc.perform(get(DETAIL).servletPath(DETAIL).session(staff)).andExpect(status().isOk())
                .andExpect(result -> {
                    String issuedAt = JsonPath.read(result.getResponse().getContentAsString(), "$.issuedAt");
                    assertEquals(AT.toInstant(), OffsetDateTime.parse(issuedAt).toInstant());
                });
        mvc.perform(command(PAY, PAY_BODY).session(staff).with(csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paidBy").value("41"));
        mvc.perform(command(RESET, RESET_BODY).session(staff).with(csrf().asHeader()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(commands).markPaid(7, 0, "CASH", "try-1", 41, "STAFF");
        verifyNoMoreInteractions(commands);
    }

    @Test void adminCanResetWithCsrfAndBothWritesRequireCsrf() throws Exception {
        var admin = session(77, "ADMIN", 0);
        mvc.perform(command(PAY, PAY_BODY).session(admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(command(RESET, RESET_BODY).session(admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(commands);

        when(commands.resetPayment(7, 1, "Nhập nhầm", "try-1", 77, "ADMIN"))
                .thenReturn(new IdempotencyExecutor.Result<>(200,
                        new InvoicePaymentCommands.PaymentResponse("7", "UNPAID", null, null, null, null, 2), false));
        mvc.perform(command(RESET, RESET_BODY).session(admin).with(csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.version").value(2));
        verify(commands).resetPayment(7, 1, "Nhập nhầm", "try-1", 77, "ADMIN");
    }

    @Test void outdatedAuthVersionRevokesTheSessionBeforeEitherWrite() throws Exception {
        var staleStaff = session(41, "STAFF", 1);
        var staleAdmin = session(77, "ADMIN", 1);
        mvc.perform(command(PAY, PAY_BODY).session(staleStaff).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(command(RESET, RESET_BODY).session(staleAdmin).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertTrue(staleStaff.isInvalid());
        assertTrue(staleAdmin.isInvalid());
        verify(users).findById(41L);
        verify(users).findById(77L);
        verifyNoInteractions(commands);
    }

    private MockHttpSession session(long id, String role, long storedVersion) {
        var user = UserEntity.create("employee", "hash", "Nhân viên", role);
        user.setAuthVersion(storedVersion);
        when(users.findById(id)).thenReturn(Optional.of(user));
        var principal = new GaragePrincipal(id, "employee", "Nhân viên", role, 0, "hash");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static MockHttpServletRequestBuilder command(String path, String body) {
        return post(path).servletPath(path).header("Idempotency-Key", "try-1")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock testClock() { return Clock.systemUTC(); }
    }
}
