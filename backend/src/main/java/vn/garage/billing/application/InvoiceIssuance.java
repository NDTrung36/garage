package vn.garage.billing.application;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.billing.domain.InvoiceStore;

@Service
public class InvoiceIssuance {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final InvoiceStore store;

    public InvoiceIssuance(InvoiceStore store) { this.store = store; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary issue(long orderId, long totalAmount, OffsetDateTime issuedAt) {
        var code = new StringBuilder("INV-");
        for (int i = 0; i < 12; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        try {
            return summary(store.insertUnpaid(orderId, code.toString(), totalAmount, issuedAt));
        } catch (InvoiceStore.CodeCollision collision) {
            throw new CodeCollision(collision);
        }
    }

    public Optional<Summary> findByRepairOrder(long orderId) {
        return store.findByRepairOrder(orderId).map(InvoiceIssuance::summary);
    }

    private static Summary summary(InvoiceStore.Invoice invoice) {
        return new Summary(Long.toString(invoice.id()), invoice.code(), invoice.totalAmount(),
                invoice.paymentStatus(), invoice.issuedAt());
    }

    public record Summary(String id, String code, long totalAmount, String paymentStatus, OffsetDateTime issuedAt) { }

    public static final class CodeCollision extends RuntimeException {
        private CodeCollision(Throwable cause) { super(cause); }
    }
}
