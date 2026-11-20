package vn.garage.billing.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import vn.garage.billing.domain.InvoicePayment;
import vn.garage.billing.domain.InvoiceStore;
import vn.garage.billing.domain.InvoiceStore.PaymentStatus;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;

@Service
public class InvoicePaymentCommands {
    private final InvoiceStore invoices;
    private final IdempotencyExecutor idempotency;
    private final AuditWriter audit;
    private final Clock clock;

    public InvoicePaymentCommands(InvoiceStore invoices, IdempotencyExecutor idempotency, AuditWriter audit, Clock clock) {
        this.invoices = invoices;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    public IdempotencyExecutor.Result<PaymentResponse> markPaid(long id, long expectedVersion, String paymentMethod,
                                                                 String key, long actorId, String actorRole) {
        if (!"STAFF".equals(actorRole) && !"ADMIN".equals(actorRole)) throw new IllegalStateException("FORBIDDEN");
        requireInput(id, expectedVersion, actorId);
        return idempotency.execute("billing.invoice.mark-paid", actorId, key,
                new MarkPaidPayload(id, expectedVersion, paymentMethod), PaymentResponse.class, () -> {
                    InvoiceStore.Details before = lockCurrent(id, expectedVersion);
                    if (PaymentStatus.PAID.name().equals(before.entry().invoice().paymentStatus())) {
                        throw new AlreadyPaid();
                    }
                    OffsetDateTime at = now();
                    InvoicePayment payment = InvoicePayment.paid(before.entry().invoice().totalAmount(),
                            paymentMethod, actorId, at);
                    return change(before, payment, at, actorId, "INVOICE_MARK_PAID", null);
                });
    }

    public IdempotencyExecutor.Result<PaymentResponse> resetPayment(long id, long expectedVersion, String rawReason,
                                                                     String key, long actorId, String actorRole) {
        if (!"ADMIN".equals(actorRole)) throw new IllegalStateException("FORBIDDEN");
        requireInput(id, expectedVersion, actorId);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("INVALID_PAYMENT_RESET_REASON");
        }
        return idempotency.execute("billing.invoice.reset-payment", actorId, key,
                new ResetPaymentPayload(id, expectedVersion, reason), PaymentResponse.class, () -> {
                    InvoiceStore.Details before = lockCurrent(id, expectedVersion);
                    if (!PaymentStatus.PAID.name().equals(before.entry().invoice().paymentStatus())) {
                        throw new NotPaid();
                    }
                    return change(before, InvoicePayment.unpaid(), now(), actorId, "INVOICE_RESET_PAYMENT", reason);
                });
    }

    private InvoiceStore.Details lockCurrent(long id, long expectedVersion) {
        var invoice = invoices.lockById(id).orElseThrow(InvoiceQuery.InvoiceNotFound::new);
        if (invoice.version() != expectedVersion) throw new StaleVersion();
        return invoice;
    }

    private IdempotencyExecutor.Success<PaymentResponse> change(InvoiceStore.Details before, InvoicePayment payment,
            OffsetDateTime at, long actorId, String action, String reason) {
        long id = before.entry().invoice().id();
        var after = invoices.updatePayment(id, before.version(), payment, at);
        var result = response(after);
        audit.write(actorId, action, "INVOICE", id, reason,
                snapshot(before, response(before)), snapshot(after, result));
        return new IdempotencyExecutor.Success<>(200, result);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static void requireInput(long id, long version, long actorId) {
        if (id < 1 || version < 0 || actorId < 1) throw new IllegalArgumentException("INVALID_INPUT");
    }

    private static PaymentResponse response(InvoiceStore.Details details) {
        var invoice = details.entry().invoice();
        return new PaymentResponse(Long.toString(invoice.id()), invoice.paymentStatus(), details.paidAmount(),
                details.paymentMethod(), details.paidBy() == null ? null : details.paidBy().toString(),
                details.paidAt(), details.version());
    }

    private static PaymentSnapshot snapshot(InvoiceStore.Details details, PaymentResponse response) {
        return new PaymentSnapshot(response.id(), details.entry().invoice().totalAmount(), response.paymentStatus(),
                response.paidAmount(), response.paymentMethod(), response.paidBy(), response.paidAt(), response.version());
    }

    private record MarkPaidPayload(long id, long expectedVersion, String paymentMethod) { }
    private record ResetPaymentPayload(long id, long expectedVersion, String reason) { }

    public record PaymentResponse(String id, String paymentStatus, Long paidAmount, String paymentMethod,
                                  String paidBy, OffsetDateTime paidAt, long version) { }

    private record PaymentSnapshot(String id, long totalAmount, String paymentStatus, Long paidAmount,
                                   String paymentMethod, String paidBy, OffsetDateTime paidAt, long version) { }

    public static final class StaleVersion extends RuntimeException { }
    public static final class AlreadyPaid extends RuntimeException { }
    public static final class NotPaid extends RuntimeException { }
}
