package vn.garage.repair.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.billing.application.InvoiceIssuance;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairLaborRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairStatus;

@Service
public class RepairOrderQuery {
    private final RepairOrderStore orders;
    private final BookingStore bookings;
    private final RepairLaborRepository labor;
    private final RepairPartQuery parts;
    private final InvoiceIssuance invoices;

    public RepairOrderQuery(RepairOrderStore orders, BookingStore bookings,
                            RepairLaborRepository labor, RepairPartQuery parts, InvoiceIssuance invoices) {
        this.orders = orders; this.bookings = bookings; this.labor = labor; this.parts = parts;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public Page list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_PAGE");
        RepairStatus parsed = parseStatus(status);
        List<ListItem> items = orders.list(parsed, page, size).stream().map(RepairOrderQuery::listItem).toList();
        long total = orders.count(parsed);
        return new Page(items, page, size, total, Math.ceilDiv(total, size));
    }

    @Transactional(readOnly = true)
    public Detail detail(long orderId) {
        if (orderId < 1) throw new IllegalArgumentException("INVALID_REPAIR_ORDER_ID");
        RepairOrderStore.RepairOrderData order = orders.findDetails(orderId)
                .orElseThrow(RepairOrderService.RepairOrderNotFound::new);
        InvoiceIssuance.Summary invoice = order.status() == RepairStatus.COMPLETED
                ? invoices.findByRepairOrder(orderId).orElseThrow(RepairCompletionService.InconsistentRepair::new)
                : null;
        RepairPartQuery.Detail partDetail = parts.detail(orderId);
        return new Detail(Long.toString(order.id()), Long.toString(order.bookingId()), order.bookingCode(),
                order.bookingSource(), order.bookingStatus(), order.status(), order.version(), order.odometerKm(),
                order.conditionNotes(), order.internalNotes(), Long.toString(order.receivedBy()), order.receivedAt(),
                order.completedBy() == null ? null : Long.toString(order.completedBy()), order.completedAt(),
                order.vehicleType(), order.contactName(), order.contactPhone(), order.plate(), order.brand(), order.model(),
                bookings.requestedServicesForBooking(order.bookingId()), labor.laborItems(orderId).stream()
                .map(RepairOrderQuery::laborView).toList(), partDetail.partItems(), partDetail.returnableIssues(),
                new Totals(partDetail.laborAmount(), partDetail.partAmount(),
                        invoice == null ? partDetail.totalAmount() : invoice.totalAmount()), invoice);
    }

    private static RepairStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return RepairStatus.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("INVALID_REPAIR_STATUS"); }
    }

    private static ListItem listItem(RepairOrderStore.RepairOrderData order) {
        return new ListItem(Long.toString(order.id()), Long.toString(order.bookingId()), order.bookingCode(),
                order.bookingSource(), order.status(), order.version(), order.vehicleType(), order.contactName(),
                order.contactPhone(), order.plate(), order.receivedAt(), order.completedAt());
    }

    private static LaborItemView laborView(RepairLaborRepository.LaborItem item) {
        return new LaborItemView(Long.toString(item.id()), item.serviceId() == null ? null : Long.toString(item.serviceId()),
                item.description(), item.unit(), item.quantity().setScale(3).toPlainString(),
                item.unitPrice().longValueExact(), item.lineTotal().longValueExact());
    }

    public record Page(List<ListItem> items, int page, int size, long totalElements, long totalPages) { }
    public record ListItem(String repairOrderId, String bookingId, String bookingCode, String bookingSource,
                           RepairStatus status, long version, String vehicleType, String contactName,
                           String contactPhone, String plate, java.time.OffsetDateTime receivedAt,
                           java.time.OffsetDateTime completedAt) { }
    public record Detail(String repairOrderId, String bookingId, String bookingCode, String bookingSource,
                         BookingStore.BookingStatus bookingStatus, RepairStatus status, long orderVersion,
                         Integer odometerKm, String conditionNotes, String internalNotes,
                         String receivedBy, java.time.OffsetDateTime receivedAt, String completedBy,
                         java.time.OffsetDateTime completedAt, String vehicleType, String contactName,
                         String contactPhone, String plate, String brand, String model,
                         List<BookingStore.RequestedService> requestedServices, List<LaborItemView> laborItems,
                         List<vn.garage.repair.application.RepairPartCommands.PartItemView> partItems,
                         List<RepairPartQuery.IssueView> returnableIssues, Totals totals, InvoiceIssuance.Summary invoice) { }
    public record LaborItemView(String id, String serviceId, String description, String unit,
                                String quantity, long unitPrice, long lineTotal) { }
    public record Totals(long laborAmount, long partAmount, long totalAmount) { }
}
