package vn.garage.inventory.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;

@Service
public class StockHistory {
    private final StockMovementRepository movements;

    public StockHistory(StockMovementRepository movements) { this.movements = movements; }

    @Transactional(readOnly = true)
    public Page list(int page, int size, Long partId, StockMovement.Type type) {
        validate(page, size, partId);
        var result = movements.list(page, size, partId, type);
        return new Page(result.items().stream().map(item -> toView(item, null, null)).toList(), page, size,
                result.totalElements(), Math.ceilDiv(result.totalElements(), size));
    }

    private static void validate(int page, int size, Long partId) {
        if (page < 0 || size < 1 || size > 100 || partId != null && partId < 1) {
            throw new IllegalArgumentException("INVALID_PAGE_OR_PART_ID");
        }
    }

    public static MovementView toView(StockMovement movement, String actorDisplayName, Long repairOrderId) {
        return new MovementView(Long.toString(movement.id()), Long.toString(movement.partId()),
                movement.partCode(), movement.type().name(), movement.quantityDelta().toPlainString(),
                movement.quantityBefore().toPlainString(), movement.quantityAfter().toPlainString(),
                movement.reason(), movement.note(), Long.toString(movement.actorId()), movement.createdAt(),
                stringId(movement.repairItemId()), stringId(movement.originalIssueId()), actorDisplayName,
                stringId(repairOrderId));
    }

    public static MovementView withHistoryMetadata(MovementView movement, String actorDisplayName, Long repairOrderId) {
        return new MovementView(movement.id(), movement.partId(), movement.partCode(), movement.movementType(),
                movement.quantityDelta(), movement.quantityBefore(), movement.quantityAfter(), movement.reason(),
                movement.note(), movement.actorId(), movement.createdAt(), movement.repairItemId(),
                movement.originalIssueId(), actorDisplayName,
                repairOrderId == null ? null : Long.toString(repairOrderId));
    }

    private static String stringId(Long value) { return value == null ? null : Long.toString(value); }

    public record MovementView(String id, String partId, String partCode, String movementType,
                               String quantityDelta, String quantityBefore, String quantityAfter,
                               String reason, String note, String actorId, java.time.OffsetDateTime createdAt,
                               String repairItemId, String originalIssueId, String actorDisplayName,
                               String repairOrderId) { }
    public record Page(List<MovementView> items, int page, int size, long totalElements, long totalPages) { }
}
