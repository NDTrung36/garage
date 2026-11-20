package vn.garage.billing.domain;

import java.time.OffsetDateTime;
import vn.garage.billing.domain.InvoiceStore.PaymentStatus;

public record InvoicePayment(PaymentStatus status, Long amount, Method method,
                             Long paidBy, OffsetDateTime paidAt) {

    public static InvoicePayment paid(long total, String rawMethod, long actorId, OffsetDateTime paidAt) {
        Method method = parseMethod(rawMethod);
        if ((total == 0) != (method == Method.NO_CHARGE)) throw new InvalidMethod();
        return new InvoicePayment(PaymentStatus.PAID, total, method, actorId, paidAt);
    }

    public static InvoicePayment unpaid() {
        return new InvoicePayment(PaymentStatus.UNPAID, null, null, null, null);
    }

    private static Method parseMethod(String value) {
        if (value == null) throw new InvalidMethod();
        try {
            return Method.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidMethod();
        }
    }

    public enum Method { CASH, BANK_TRANSFER, NO_CHARGE }

    public static final class InvalidMethod extends IllegalArgumentException {
        public InvalidMethod() { super("INVALID_PAYMENT_METHOD"); }
    }
}
