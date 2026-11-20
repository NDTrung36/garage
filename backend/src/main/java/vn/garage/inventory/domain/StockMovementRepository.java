package vn.garage.inventory.domain;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface StockMovementRepository {
    record LockedPart(long id, String code, String name, String unit, String vehicleType,
                      long referencePrice, boolean active, StockQuantity stockQuantity) {
        public LockedPart(long id, String code, StockQuantity stockQuantity) {
            this(id, code, "", "", "BOTH", 0, true, stockQuantity);
        }
    }
    record IssueReference(long id, long partId, long repairItemId, StockQuantity issuedQuantity,
                          StockQuantity remainingQuantity, java.time.OffsetDateTime createdAt) { }
    record NewMovement(long partId, String partCode, Long repairItemId, Long originalIssueId, StockMovement.Type type,
                       StockQuantity quantityDelta, StockQuantity quantityBefore, StockQuantity quantityAfter,
                       String reason, String note, long actorId) { }
    record MovementPage(List<StockMovement> items, long totalElements) { }

    Optional<LockedPart> lockPart(long partId);
    void updateStock(long partId, StockQuantity stockQuantity);
    StockMovement insert(NewMovement movement);
    MovementPage list(int page, int size, Long partId, StockMovement.Type type);
    Optional<StockMovement> findIssue(long issueId);
    Optional<StockMovement> lockIssue(long issueId);
    StockQuantity returnedQuantity(long issueId);
    List<IssueReference> issuesForRepairItems(Collection<Long> repairItemIds);
    record ItemNet(long repairItemId, long partId, java.math.BigDecimal quantity) { }
    List<ItemNet> netForRepairItems(List<Long> repairItemIds);
}
