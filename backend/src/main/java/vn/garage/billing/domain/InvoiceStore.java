package vn.garage.billing.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface InvoiceStore {
    Optional<Invoice> findByRepairOrder(long repairOrderId);

    Optional<Details> findById(long id);

    Optional<Details> lockById(long id);

    Details updatePayment(long id, long expectedVersion, InvoicePayment payment, OffsetDateTime updatedAt);

    List<ListEntry> list(PaymentStatus status, int page, int size);

    long count(PaymentStatus status);

    Invoice insertUnpaid(long repairOrderId, String code, long totalAmount, OffsetDateTime issuedAt);

    record Invoice(long id, String code, long totalAmount, String paymentStatus, OffsetDateTime issuedAt) { }

    enum PaymentStatus { UNPAID, PAID }

    record ListEntry(Invoice invoice, long repairOrderId) { }

    record Details(ListEntry entry, long version, Long paidAmount, String paymentMethod,
                   Long paidBy, OffsetDateTime paidAt) { }

    final class CodeCollision extends RuntimeException {
        public CodeCollision(Throwable cause) { super(cause); }
    }
}
