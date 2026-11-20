package vn.garage.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.billing.application.InvoiceQuery;
import vn.garage.billing.domain.InvoicePayment;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.support.idempotency.IdempotencyExecutor;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvoiceApiTest {
    private static final String STAFF = "/api/staff/invoices";
    private static final String PAY = STAFF + "/7/mark-paid";
    private static final String RESET = "/api/admin/invoices/7/reset-payment";
    private static final String PAY_BODY = "{\"expectedVersion\":0,\"paymentMethod\":\"CASH\"}";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-19T08:00:00Z");
    private final InvoiceQuery query = mock(InvoiceQuery.class);
    private final InvoicePaymentCommands commands = mock(InvoicePaymentCommands.class);
    private final InvoiceRequests requests = new InvoiceRequests(new ObjectMapper());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new StaffInvoicesController(query, commands, requests),
            new AdminInvoicePaymentsController(commands, requests))
            .setControllerAdvice(new BillingErrors()).build();

    @Test void readsListAndDetailWithStableDtoAndNoStore() throws Exception {
        var item = new InvoiceQuery.ListItem("7", "INV-123456789ABC", "19", AT, 51, "UNPAID");
        when(query.list(null, 0, 20)).thenReturn(new InvoiceQuery.Page(List.of(item), 0, 20, 1, 1));
        when(query.list("PAID", 2, 3)).thenReturn(new InvoiceQuery.Page(List.of(), 2, 3, 0, 0));
        when(query.detail(7)).thenReturn(new InvoiceQuery.Detail("7", item.code(), "19", AT, 51, "UNPAID",
                0, null, null, null, null, null, List.of()));
        mvc.perform(get(STAFF)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].repairOrderId").value("19"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(STAFF).param("paymentStatus", "PAID").param("page", "2").param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
        mvc.perform(get(STAFF + "/7")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test void paymentResponsePreservesReplayStatusAndUsesPrincipal() throws Exception {
        var paid = new InvoicePaymentCommands.PaymentResponse("7", "PAID", 51L, "CASH", "41", AT, 1);
        when(commands.markPaid(7, 0, "CASH", "key-1", 41, "ADMIN"))
                .thenReturn(new IdempotencyExecutor.Result<>(200, paid, true));
        mvc.perform(command(PAY, PAY_BODY).header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paidBy").value("41")).andExpect(jsonPath("$.paidAmount").value(51))
                .andExpect(jsonPath("$.version").value(1));
        verify(commands).markPaid(7, 0, "CASH", "key-1", 41, "ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-7", "1.5", "abc", "9223372036854775808"})
    void invalidInvoiceIdsNeverReachUseCases(String id) throws Exception {
        mvc.perform(get(STAFF + "/" + id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(query, commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{", "{\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":-1,\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":0.0,\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":\"0\",\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":9223372036854775808,\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":0,\"expectedVersion\":1,\"paymentMethod\":\"CASH\"}",
            "{\"expectedVersion\":0,\"paymentMethod\":\"CASH\"} {}"
    })
    void invalidJsonAndVersionsNeverReachWriting(String body) throws Exception {
        mvc.perform(command(PAY, body).header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paidAmount", "paidBy", "paidAt", "role", "id", "actorId"})
    void bothCommandsRejectUnknownFields(String field) throws Exception {
        String extra = ",\"" + field + "\":null}";
        mvc.perform(command(PAY, "{\"expectedVersion\":0,\"paymentMethod\":\"CASH\"" + extra)
                .header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        mvc.perform(command(RESET, "{\"expectedVersion\":0,\"reason\":\"Nhầm\"" + extra)
                .header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        verifyNoInteractions(commands);
    }

    @Test void rejectsInvalidKeysTextFieldsAndPageBinding() throws Exception {
        mvc.perform(command(PAY, PAY_BODY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(command(PAY, PAY_BODY).header("Idempotency-Key", "bad key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(command(PAY, "{\"expectedVersion\":0,\"paymentMethod\":true}").header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
        mvc.perform(command(RESET, "{\"expectedVersion\":1,\"reason\":null}").header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_RESET_REASON"));
        mvc.perform(get(STAFF).param("size", "1.5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(query, commands);
    }

    @Test void mapsUseCaseErrorsToBillingContract() throws Exception {
        when(query.detail(7)).thenThrow(new InvoiceQuery.InvoiceNotFound(), new RepairCompletionService.InconsistentRepair());
        mvc.perform(get(STAFF + "/7")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND")).andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").isString());
        mvc.perform(get(STAFF + "/7")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVOICE_DATA_INCONSISTENT"));
        when(commands.markPaid(7, 0, "CASH", "key", 41, "ADMIN"))
                .thenThrow(new InvoicePayment.InvalidMethod(), new InvoicePaymentCommands.StaleVersion(),
                        new InvoicePaymentCommands.AlreadyPaid());
        mvc.perform(command(PAY, PAY_BODY).header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
        mvc.perform(command(PAY, PAY_BODY).header("Idempotency-Key", "key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(command(PAY, PAY_BODY).header("Idempotency-Key", "key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALREADY_PAID"));
        when(commands.resetPayment(7, 0, "Nhầm", "key", 41, "ADMIN"))
                .thenThrow(new InvoicePaymentCommands.NotPaid());
        mvc.perform(command(RESET, "{\"expectedVersion\":0,\"reason\":\"Nhầm\"}").header("Idempotency-Key", "key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_NOT_PAID"));
    }

    private static MockHttpServletRequestBuilder command(String path, String body) {
        var actor = new GaragePrincipal(41, "admin", "Admin", "ADMIN", 0, "hash");
        return post(path).principal(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
