package vn.garage.inventory.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;

/** Inventory-owned port for repair orders. All writes join the caller's transaction. */
@Service
public class RepairStockOperations {
    private final StockMovementRepository movements;

    public RepairStockOperations(StockMovementRepository movements) { this.movements = movements; }

    public PartSnapshot prepareIssue(long partId, String repairVehicleType) {
        requirePositiveId(partId);
        StockMovementRepository.LockedPart part = movements.lockPart(partId).orElseThrow(PartNotFound::new);
        if (!part.active()) throw new PartInactive();
        if (!"BOTH".equals(part.vehicleType()) && !part.vehicleType().equals(repairVehicleType)) {
            throw new PartVehicleMismatch();
        }
        return new PartSnapshot(part.id(), part.code(), part.name(), part.unit(), part.vehicleType(),
                part.referencePrice(), part.stockQuantity());
    }

    public MovementResult issue(long partId, long repairItemId, StockQuantity quantity, long actorId) {
        requireTransaction();
        requirePositiveId(partId);
        requirePositiveId(repairItemId);
        requirePositiveId(actorId);
        requirePositiveQuantity(quantity);
        StockMovementRepository.LockedPart part = movements.lockPart(partId).orElseThrow(PartNotFound::new);
        if (!part.active()) throw new PartInactive();
        StockQuantity delta = quantity.negate();
        StockQuantity before = part.stockQuantity();
        StockQuantity after = before.add(delta);
        if (after.isNegative()) throw new InsufficientStock();
        movements.updateStock(partId, after);
        StockMovement created = movements.insert(new StockMovementRepository.NewMovement(partId, part.code(),
                repairItemId, null, StockMovement.Type.ISSUE, delta, before, after,
                "Xuất phụ tùng cho phiếu sửa chữa", null, actorId));
        return new MovementResult(created, after);
    }

    /** Unlocked lookup is only a hint for determining lock order. Re-read with a lock after locking the part. */
    public Optional<StockMovement> findIssue(long issueId) { return movements.findIssue(issueId); }

    public MovementResult returnIssue(long issueId, long partId, long repairItemId,
                                      StockQuantity quantity, long actorId) {
        requireTransaction();
        requirePositiveId(issueId);
        requirePositiveId(partId);
        requirePositiveId(repairItemId);
        requirePositiveId(actorId);
        requirePositiveQuantity(quantity);
        StockMovementRepository.LockedPart part = movements.lockPart(partId).orElseThrow(PartNotFound::new);
        StockMovement issue = movements.lockIssue(issueId).orElseThrow(IssueNotFound::new);
        if (issue.type() != StockMovement.Type.ISSUE || issue.partId() != partId
                || !Long.valueOf(repairItemId).equals(issue.repairItemId())) throw new IssueNotFound();
        StockQuantity issued = issue.quantityDelta().negate();
        StockQuantity remaining = issued.add(movements.returnedQuantity(issueId).negate());
        if (quantity.isNegative() || quantity.isZero() || quantity.compareTo(remaining) > 0) {
            throw new ReturnExceedsIssue();
        }
        StockQuantity before = part.stockQuantity();
        StockQuantity after;
        try {
            after = before.add(quantity);
        } catch (StockQuantity.OutOfRange outOfRange) {
            throw new StockLimitExceeded();
        }
        movements.updateStock(partId, after);
        StockMovement created = movements.insert(new StockMovementRepository.NewMovement(partId, part.code(),
                repairItemId, issueId, StockMovement.Type.RETURN, quantity, before, after,
                "Hoàn phụ tùng theo phiếu sửa chữa", null, actorId));
        return new MovementResult(created, after);
    }

    public List<StockMovementRepository.IssueReference> issuesForRepairItems(Collection<Long> repairItemIds) {
        if (repairItemIds == null || repairItemIds.isEmpty()) return List.of();
        java.util.ArrayList<StockMovementRepository.IssueReference> issues = new java.util.ArrayList<>();
        java.util.List<Long> ids = new java.util.ArrayList<>(repairItemIds);
        for (int start = 0; start < ids.size(); start += 1000) {
            issues.addAll(movements.issuesForRepairItems(ids.subList(start, Math.min(start + 1000, ids.size()))));
        }
        issues.sort(java.util.Comparator.comparing(StockMovementRepository.IssueReference::createdAt).reversed()
                .thenComparing(StockMovementRepository.IssueReference::id, java.util.Comparator.reverseOrder()));
        return List.copyOf(issues);
    }

    /** The caller holds the repair order lock, which serializes writes to these ledger entries. */
    public List<ItemNet> netForRepairItems(List<Long> repairItemIds) {
        requireTransaction();
        var result = new java.util.ArrayList<ItemNet>();
        for (int start = 0; start < repairItemIds.size(); start += 1000) {
            var batch = repairItemIds.subList(start, Math.min(start + 1000, repairItemIds.size()));
            for (var row : movements.netForRepairItems(batch)) {
                result.add(new ItemNet(row.repairItemId(), row.partId(), row.quantity()));
            }
        }
        return List.copyOf(result);
    }

    public record ItemNet(long repairItemId, long partId, java.math.BigDecimal quantity) { }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("REPAIR_STOCK_REQUIRES_ACTIVE_TRANSACTION");
        }
    }

    private static void requirePositiveId(long value) {
        if (value < 1) throw new IllegalArgumentException("INVALID_REPAIR_STOCK_ID");
    }

    private static void requirePositiveQuantity(StockQuantity quantity) {
        if (quantity == null || !quantity.isPositive()) throw new IllegalArgumentException("INVALID_STOCK_QUANTITY");
    }

    public record PartSnapshot(long id, String code, String name, String unit, String vehicleType,
                               long referencePrice, StockQuantity stockQuantity) { }
    public record MovementResult(StockMovement movement, StockQuantity stockQuantity) { }
    public static final class PartNotFound extends RuntimeException { }
    public static final class PartInactive extends RuntimeException { }
    public static final class PartVehicleMismatch extends RuntimeException { }
    public static final class InsufficientStock extends RuntimeException { }
    public static final class IssueNotFound extends RuntimeException { }
    public static final class ReturnExceedsIssue extends RuntimeException { }
    public static final class StockLimitExceeded extends RuntimeException { }
}
