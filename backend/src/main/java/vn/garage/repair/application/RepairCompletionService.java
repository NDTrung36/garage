package vn.garage.repair.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import org.springframework.stereotype.Service;
import vn.garage.billing.application.InvoiceIssuance;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.BookingStore.BookingStatus;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@Service
public class RepairCompletionService {
    private static final int MAX_CODE_ATTEMPTS = 3;
    private final BookingStore bookings;
    private final RepairOrderStore orders;
    private final RepairPartRepository items;
    private final RepairStockOperations stock;
    private final InvoiceIssuance invoices;
    private final SqlServerWriteTransactions writes;
    private final Clock clock;

    public RepairCompletionService(BookingStore bookings, RepairOrderStore orders, RepairPartRepository items,
                                   RepairStockOperations stock, InvoiceIssuance invoices,
                                   SqlServerWriteTransactions writes, Clock clock) {
        this.bookings = bookings;
        this.orders = orders;
        this.items = items;
        this.stock = stock;
        this.invoices = invoices;
        this.writes = writes;
        this.clock = clock;
    }

    public Result complete(long orderId, long expectedVersion, long actorId, String role) {
        if (!"STAFF".equals(role) && !"ADMIN".equals(role)) throw new IllegalStateException("FORBIDDEN");
        if (orderId < 1 || actorId < 1 || expectedVersion < 0) throw new IllegalArgumentException("INVALID_INPUT");

        for (int attempt = 1; ; attempt++) {
            try {
                return writes.execute(() -> completeInTransaction(orderId, expectedVersion, actorId));
            } catch (InvoiceIssuance.CodeCollision collision) {
                if (attempt == MAX_CODE_ATTEMPTS) throw new InvoiceCodeRetriesExhausted(collision);
            }
        }
    }

    private Result completeInTransaction(long orderId, long expectedVersion, long actorId) {
        long bookingId = orders.findBookingId(orderId).orElseThrow(RepairOrderService.RepairOrderNotFound::new);
        var booking = bookings.lockForCheckIn(bookingId).orElseThrow(InconsistentRepair::new);
        var order = orders.findByIdForUpdate(orderId).orElseThrow(InconsistentRepair::new);

        if (order.status() == RepairStatus.COMPLETED) {
            if (booking.status() != BookingStatus.COMPLETED) throw new InconsistentRepair();
            var details = orders.findDetails(orderId).orElseThrow(InconsistentRepair::new);
            var invoice = invoices.findByRepairOrder(orderId).orElseThrow(InconsistentRepair::new);
            return result(orderId, bookingId, order.version(), details.completedAt(), invoice);
        }
        if (booking.status() != BookingStatus.CHECKED_IN) throw new InconsistentRepair();
        if (order.version() != expectedVersion) throw new RepairOrderService.StaleVersion();

        verifyPartQuantities(orderId);
        var totals = RepairTotals.calculate(items.totalLines(orderId).stream()
                .map(line -> new RepairTotals.Line(line.type(), line.quantity(), line.unitPrice(), line.voided()))
                .toList());
        var completedAt = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        if (!bookings.markCompleted(bookingId, booking.version(), completedAt)
                || !orders.markCompleted(orderId, order.version(), actorId, completedAt)) {
            throw new InconsistentRepair();
        }
        var invoice = invoices.issue(orderId, totals.totalAmount().longValueExact(), completedAt);
        return result(orderId, bookingId, order.version() + 1, completedAt, invoice);
    }

    private void verifyPartQuantities(long orderId) {
        var parts = items.partItems(orderId);
        var ledger = stock.netForRepairItems(parts.stream().map(RepairPartRepository.PartItem::id).toList());
        var byItem = new HashMap<Long, RepairStockOperations.ItemNet>();
        for (var net : ledger) {
            if (byItem.putIfAbsent(net.repairItemId(), net) != null) throw new InconsistentRepair();
        }
        for (var part : parts) {
            var net = byItem.get(part.id());
            if (net != null && net.partId() != part.partId()) throw new InconsistentRepair();
            if (part.quantity().compareTo(net == null ? BigDecimal.ZERO : net.quantity()) != 0) {
                throw new InconsistentRepair();
            }
        }
    }

    private static Result result(long orderId, long bookingId, long version, OffsetDateTime completedAt,
                                 InvoiceIssuance.Summary invoice) {
        return new Result(Long.toString(orderId), Long.toString(bookingId), RepairStatus.COMPLETED,
                BookingStatus.COMPLETED, version, completedAt, invoice);
    }

    public record Result(String repairOrderId, String bookingId, RepairStatus status, BookingStatus bookingStatus,
                         long version, OffsetDateTime completedAt, InvoiceIssuance.Summary invoice) { }

    public static final class InconsistentRepair extends RuntimeException { }

    public static final class InvoiceCodeRetriesExhausted extends RuntimeException {
        private InvoiceCodeRetriesExhausted(Throwable cause) { super(cause); }
    }
}
