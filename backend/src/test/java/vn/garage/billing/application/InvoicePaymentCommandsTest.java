package vn.garage.billing.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.garage.billing.domain.InvoicePayment;
import vn.garage.billing.domain.InvoiceStore;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoicePaymentCommandsTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-19T08:00:00Z");
    private final IdempotencyExecutor executor = mock(IdempotencyExecutor.class);
    private final InvoiceStore store = mock(InvoiceStore.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final InvoicePaymentCommands commands = new InvoicePaymentCommands(store, executor, audit, Clock.systemUTC());

    @ParameterizedTest
    @CsvSource({"0,NO_CHARGE", "150,CASH", "150,BANK_TRANSFER"})
    void acceptsMethodsForTheCorrectTotal(long total, String method) {
        var payment = InvoicePayment.paid(total, method, 3, AT);
        assertEquals(total, payment.amount());
        assertEquals(method, payment.method().name());
    }

    @ParameterizedTest
    @CsvSource({"0,CASH", "0,BANK_TRANSFER", "150,NO_CHARGE", "150,card", "150,"})
    void rejectsMethodsOutsideTheContract(long total, String method) {
        assertThrows(InvoicePayment.InvalidMethod.class, () -> InvoicePayment.paid(total, method, 3, AT));
    }

    @Test void rejectsBlankAndOversizedReasonsBeforeWriting() {
        for (String reason : new String[]{" \t ", null, "x".repeat(501)}) {
            assertEquals("INVALID_PAYMENT_RESET_REASON", assertThrows(IllegalArgumentException.class,
                    () -> commands.resetPayment(1, 0, reason, "key", 3, "ADMIN")).getMessage());
        }
        verifyNoInteractions(executor, store, audit);
    }

    @Test void rejectsInvalidRoleAndVersionBeforeWriting() {
        assertThrows(IllegalStateException.class, () -> commands.resetPayment(1, 0, "lý do", "key", 3, "STAFF"));
        assertThrows(IllegalStateException.class, () -> commands.markPaid(1, 0, "CASH", "key", 3, "GUEST"));
        assertThrows(IllegalArgumentException.class, () -> commands.markPaid(1, -1, "CASH", "key", 3, "STAFF"));
        verifyNoInteractions(executor, store, audit);
    }
}
