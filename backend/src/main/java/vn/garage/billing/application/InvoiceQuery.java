package vn.garage.billing.application;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.billing.domain.InvoiceStore;
import vn.garage.billing.domain.InvoiceStore.PaymentStatus;
import vn.garage.identity.application.StaffDirectory;
import vn.garage.repair.application.RepairInvoiceLines;

@Service
public class InvoiceQuery {
    private final InvoiceStore invoices;
    private final RepairInvoiceLines repairLines;
    private final StaffDirectory staff;

    public InvoiceQuery(InvoiceStore invoices, RepairInvoiceLines repairLines, StaffDirectory staff) {
        this.invoices = invoices;
        this.repairLines = repairLines;
        this.staff = staff;
    }

    @Transactional(readOnly = true)
    public Page list(String paymentStatus, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_INPUT");
        PaymentStatus status = parseStatus(paymentStatus);
        var items = invoices.list(status, page, size).stream().map(InvoiceQuery::listItem).toList();
        long total = invoices.count(status);
        return new Page(items, page, size, total, Math.ceilDiv(total, size));
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        if (id < 1) throw new IllegalArgumentException("INVALID_INPUT");
        var details = invoices.findById(id).orElseThrow(InvoiceNotFound::new);
        var entry = details.entry();
        var header = listItem(entry);
        var lines = repairLines.forCompletedOrder(entry.repairOrderId());
        Long paidBy = details.paidBy();
        String displayName = paidBy == null ? null : staff.displayNames(List.of(paidBy)).get(paidBy);
        return new Detail(header.id(), header.code(), header.repairOrderId(), header.issuedAt(),
                header.totalAmount(), header.paymentStatus(), details.version(), details.paidAmount(),
                details.paymentMethod(), paidBy == null ? null : paidBy.toString(), displayName, details.paidAt(), lines);
    }

    private static PaymentStatus parseStatus(String value) {
        if (value == null) return null;
        try {
            return PaymentStatus.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INVALID_INPUT", invalid);
        }
    }

    private static ListItem listItem(InvoiceStore.ListEntry entry) {
        var invoice = entry.invoice();
        return new ListItem(Long.toString(invoice.id()), invoice.code(), Long.toString(entry.repairOrderId()),
                invoice.issuedAt(), invoice.totalAmount(), invoice.paymentStatus());
    }

    public record Page(List<ListItem> items, int page, int size, long totalElements, long totalPages) { }

    public record ListItem(String id, String code, String repairOrderId, OffsetDateTime issuedAt,
                           long totalAmount, String paymentStatus) { }

    public record Detail(String id, String code, String repairOrderId, OffsetDateTime issuedAt,
                         long totalAmount, String paymentStatus, long version, Long paidAmount,
                         String paymentMethod, String paidBy, String paidByDisplayName, OffsetDateTime paidAt,
                         List<RepairInvoiceLines.Line> items) { }

    public static final class InvoiceNotFound extends RuntimeException { }
}
