package vn.garage.repair.application;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@Service
public class RepairPartCommands {
    private static final String ISSUE_SCOPE = "repair.part.issue";
    private static final String RETURN_SCOPE = "repair.part.return";
    private final RepairOrderStore orders;
    private final RepairPartRepository repairs;
    private final RepairStockOperations stock;
    private final IdempotencyExecutor idempotency;
    private final SqlServerWriteTransactions writes;
    private final AuditWriter audit;

    public RepairPartCommands(RepairOrderStore orders, RepairPartRepository repairs, RepairStockOperations stock,
                              IdempotencyExecutor idempotency, SqlServerWriteTransactions writes,
                              AuditWriter audit) {
        this.orders = orders;
        this.repairs = repairs;
        this.stock = stock;
        this.idempotency = idempotency;
        this.writes = writes;
        this.audit = audit;
    }

    public IdempotencyExecutor.Result<CommandResponse> issue(long orderId, long partId, String rawQuantity,
                                                              String key, long actorId, String role) {
        requireStaff(actorId, role);
        requireId(orderId, "INVALID_REPAIR_ORDER_ID");
        requireId(partId, "INVALID_PART_ID");
        StockQuantity quantity = StockQuantity.parsePositive(rawQuantity);
        return idempotency.execute(ISSUE_SCOPE, actorId, key,
                new IssuePayload(orderId, partId, quantity.toPlainString()), CommandResponse.class,
                () -> new IdempotencyExecutor.Success<>(201, issueInTransaction(orderId, partId, quantity, actorId)));
    }

    public IdempotencyExecutor.Result<CommandResponse> returnPart(long orderId, long issueId,
            String rawQuantity, String key, long actorId, String role) {
        requireStaff(actorId, role);
        requireId(orderId, "INVALID_REPAIR_ORDER_ID");
        requireId(issueId, "INVALID_ISSUE_ID");
        StockQuantity quantity = StockQuantity.parsePositive(rawQuantity);
        return idempotency.execute(RETURN_SCOPE, actorId, key,
                new ReturnPayload(orderId, issueId, quantity.toPlainString()), CommandResponse.class,
                () -> new IdempotencyExecutor.Success<>(201,
                        returnInTransaction(orderId, issueId, quantity, actorId)));
    }

    public PriceResponse updatePrice(long orderId, long itemId, long unitPrice, String rawReason,
                                     long expectedVersion, long actorId, String role) {
        requireStaff(actorId, role);
        requireId(orderId, "INVALID_REPAIR_ORDER_ID");
        requireId(itemId, "INVALID_PART_ITEM_ID");
        if (unitPrice < 0 || unitPrice > 9_007_199_254_740_991L) throw new IllegalArgumentException("INVALID_UNIT_PRICE");
        if (expectedVersion < 0) throw new IllegalArgumentException("INVALID_EXPECTED_VERSION");
        String reason = requiredReason(rawReason);
        return writes.execute(() -> updatePriceInTransaction(orderId, itemId, unitPrice, reason,
                expectedVersion, actorId));
    }

    private CommandResponse issueInTransaction(long orderId, long partId, StockQuantity quantity, long actorId) {
        RepairOrderStore.RepairOrderContext order = lockInProgress(orderId);
        RepairStockOperations.PartSnapshot part = stock.prepareIssue(partId, order.vehicleType());
        RepairPartRepository.PartItem item = repairs.findPartItem(orderId, partId)
                .orElseGet(() -> repairs.insertPartItem(orderId, partId, part.name(), part.unit(), part.referencePrice()));

        RepairStockOperations.MovementResult movement = stock.issue(partId, item.id(), quantity, actorId);
        BigDecimal nextQuantity = checkedQuantity(item.quantity().add(quantity.value()));
        RepairPartRepository.PartItem updated = repairs.updatePartQuantity(orderId, item.id(), nextQuantity);
        long version = repairs.incrementOrderVersion(orderId);
        RepairTotals.Totals totals = totals(orderId);
        return response(movement, updated, version, totals);
    }

    private CommandResponse returnInTransaction(long orderId, long issueId, StockQuantity quantity, long actorId) {
        RepairOrderStore.RepairOrderContext order = lockInProgress(orderId);
        StockMovement reference = stock.findIssue(issueId).orElseThrow(RepairStockOperations.IssueNotFound::new);
        Long repairItemId = reference.repairItemId();
        if (reference.type() != StockMovement.Type.ISSUE || repairItemId == null) {
            throw new RepairStockOperations.IssueNotFound();
        }
        RepairPartRepository.PartItem item = repairs.findPartItemById(order.id(), repairItemId)
                .filter(value -> value.partId() == reference.partId())
                .orElseThrow(RepairStockOperations.IssueNotFound::new);
        RepairStockOperations.MovementResult movement = stock.returnIssue(issueId, reference.partId(), item.id(),
                quantity, actorId);
        BigDecimal nextQuantity = item.quantity().subtract(quantity.value());
        if (nextQuantity.signum() < 0) throw new RepairStockOperations.ReturnExceedsIssue();
        RepairPartRepository.PartItem updated = repairs.updatePartQuantity(orderId, item.id(),
                checkedQuantity(nextQuantity));
        long version = repairs.incrementOrderVersion(orderId);
        RepairTotals.Totals totals = totals(orderId);
        return response(movement, updated, version, totals);
    }

    private PriceResponse updatePriceInTransaction(long orderId, long itemId, long unitPrice, String reason,
                                                    long expectedVersion, long actorId) {
        RepairOrderStore.RepairOrderContext order = lockInProgress(orderId);
        if (order.version() != expectedVersion) throw new StaleVersion();
        RepairPartRepository.PartItem current = repairs.findPartItemById(orderId, itemId)
                .orElseThrow(PartItemNotFound::new);
        RepairTotals.Totals beforeTotals = totals(orderId);
        if (current.unitPrice().longValueExact() == unitPrice) {
            return new PriceResponse(view(current), order.version(), safeLong(beforeTotals.totalAmount()));
        }
        RepairPartRepository.PartItem updated = repairs.updatePartPrice(orderId, itemId, unitPrice);
        RepairTotals.Totals afterTotals = totals(orderId);
        long version = repairs.incrementOrderVersion(orderId);
        audit.write(actorId, "REPAIR_PART_PRICE_UPDATED", "REPAIR_ITEM", itemId, reason,
                new PriceAuditSnapshot(current.quantity().setScale(3).toPlainString(),
                        current.unitPrice().longValueExact(), safeLong(current.lineTotal()),
                        safeLong(beforeTotals.totalAmount())),
                new PriceAuditSnapshot(updated.quantity().setScale(3).toPlainString(), unitPrice,
                        safeLong(updated.lineTotal()), safeLong(afterTotals.totalAmount())));
        return new PriceResponse(view(updated), version, safeLong(afterTotals.totalAmount()));
    }

    private RepairOrderStore.RepairOrderContext lockInProgress(long orderId) {
        // Read the immutable booking snapshot before the order lock. Completion locks booking → order.
        RepairOrderStore.RepairOrderContext hint = orders.findById(orderId)
                .orElseThrow(RepairOrderNotFound::new);
        RepairOrderStore.RepairOrderContext locked = orders.findByIdForUpdate(orderId)
                .orElseThrow(RepairOrderNotFound::new);
        RepairOrderStore.RepairOrderContext order = new RepairOrderStore.RepairOrderContext(locked.id(),
                locked.status(), locked.version(), hint.vehicleType());
        if (order.status() != RepairStatus.IN_PROGRESS) throw new RepairNotInProgress();
        return order;
    }

    private RepairTotals.Totals totals(long orderId) {
        return RepairTotals.calculate(repairs.totalLines(orderId).stream().map(line -> new RepairTotals.Line(
                line.type(), line.quantity(), line.unitPrice(), line.voided())).toList());
    }

    private static BigDecimal checkedQuantity(BigDecimal value) {
        try { return new StockQuantity(value).value(); }
        catch (StockQuantity.OutOfRange invalid) { throw new RepairStockOperations.StockLimitExceeded(); }
    }

    private static CommandResponse response(RepairStockOperations.MovementResult movement,
            RepairPartRepository.PartItem item, long version, RepairTotals.Totals totals) {
        return new CommandResponse(StockHistory.toView(movement.movement(), null, null),
                movement.stockQuantity().toPlainString(), view(item), version, safeLong(totals.totalAmount()));
    }

    public static PartItemView view(RepairPartRepository.PartItem item) {
        return new PartItemView(Long.toString(item.id()), Long.toString(item.partId()), item.description(), item.unit(),
                item.quantity().setScale(3).toPlainString(), item.unitPrice().longValueExact(), safeLong(item.lineTotal()));
    }

    private static long safeLong(BigDecimal amount) { return amount.longValueExact(); }

    private static String requiredReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("INVALID_REASON");
        return reason;
    }

    private static void requireId(long value, String code) {
        if (value < 1) throw new IllegalArgumentException(code);
    }

    private static void requireStaff(long actorId, String role) {
        if (!"STAFF".equals(role) && !"ADMIN".equals(role)) throw new IllegalStateException("FORBIDDEN");
        requireId(actorId, "INVALID_ACTOR_ID");
    }

    public record IssuePayload(long repairOrderId, long partId, String quantity) { }
    public record ReturnPayload(long repairOrderId, long originalIssueId, String quantity) { }
    public record PartItemView(String id, String partId, String description, String unit,
                               String quantity, long unitPrice, long lineTotal) { }
    public record CommandResponse(StockHistory.MovementView movement, String stockQuantity,
                                  PartItemView partItem, long orderVersion, long repairTotal) { }
    public record PriceResponse(PartItemView partItem, long orderVersion, long repairTotal) { }
    public record PriceAuditSnapshot(String quantity, long unitPrice, long lineTotal, long repairTotal) { }

    public static final class RepairOrderNotFound extends RuntimeException { }
    public static final class RepairNotInProgress extends RuntimeException { }
    public static final class PartItemNotFound extends RuntimeException { }
    public static final class StaleVersion extends RuntimeException { }
}
