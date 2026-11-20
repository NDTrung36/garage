package vn.garage.inventory.application;

import java.util.Objects;
import org.springframework.stereotype.Service;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.support.idempotency.IdempotencyExecutor;

@Service
public class StockCommands {
    private static final String RECEIPT = "inventory.stock.receipt";
    private static final String ADJUSTMENT = "inventory.stock.adjustment";
    private final StockMovementRepository movements;
    private final IdempotencyExecutor idempotency;

    public StockCommands(StockMovementRepository movements, IdempotencyExecutor idempotency) {
        this.movements = movements;
        this.idempotency = idempotency;
    }

    public IdempotencyExecutor.Result<CommandResponse> receipt(long partId, String rawQuantity,
            String rawReason, String rawNote, String key, long actorId, String actorRole) {
        requireAdmin(actorId, actorRole);
        requirePartId(partId);
        StockQuantity quantity = StockQuantity.parsePositive(rawQuantity);
        String reason = requiredReason(rawReason);
        String note = optionalNote(rawNote);
        var payload = new ReceiptPayload(partId, quantity.toPlainString(), reason, note);
        return idempotency.execute(RECEIPT, actorId, key, payload, CommandResponse.class,
                () -> new IdempotencyExecutor.Success<>(201, apply(partId, StockMovement.Type.RECEIPT,
                        quantity, reason, note, actorId)));
    }

    public IdempotencyExecutor.Result<CommandResponse> adjust(long partId, String rawDelta,
            String rawReason, String key, long actorId, String actorRole) {
        requireAdmin(actorId, actorRole);
        requirePartId(partId);
        StockQuantity delta = StockQuantity.parseDelta(rawDelta);
        String reason = requiredReason(rawReason);
        var payload = new AdjustmentPayload(partId, delta.toPlainString(), reason);
        return idempotency.execute(ADJUSTMENT, actorId, key, payload, CommandResponse.class,
                () -> new IdempotencyExecutor.Success<>(201, apply(partId, StockMovement.Type.ADJUSTMENT,
                        delta, reason, null, actorId)));
    }

    private CommandResponse apply(long partId, StockMovement.Type type, StockQuantity delta,
                                  String reason, String note, long actorId) {
        StockMovementRepository.LockedPart part = movements.lockPart(partId).orElseThrow(PartNotFound::new);
        StockQuantity before = part.stockQuantity();
        final StockQuantity after;
        try {
            after = before.add(delta);
        } catch (StockQuantity.OutOfRange outOfRange) {
            throw new StockLimitExceeded();
        }
        if (after.isNegative()) throw new StockLimitExceeded();

        movements.updateStock(partId, after);
        var created = movements.insert(new StockMovementRepository.NewMovement(partId, part.code(), null, null,
                type, delta, before, after, reason, note, actorId));
        return new CommandResponse(StockHistory.toView(created, null, null), after.toPlainString());
    }

    private static String requiredReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("INVALID_STOCK_REASON");
        return reason;
    }

    private static String optionalNote(String value) {
        if (value == null || value.isBlank()) return null;
        String note = value.trim();
        if (note.length() > 500) throw new IllegalArgumentException("INVALID_STOCK_NOTE");
        return note;
    }

    private static void requirePartId(long partId) {
        if (partId < 1) throw new IllegalArgumentException("INVALID_PART_ID");
    }

    private static void requireAdmin(long actorId, String role) {
        if (!"ADMIN".equals(role)) throw new IllegalStateException("FORBIDDEN");
        if (actorId < 1) throw new IllegalArgumentException("INVALID_ACTOR_ID");
    }

    public record CommandResponse(StockHistory.MovementView movement, String stockQuantity) { }
    public record ReceiptPayload(long partId, String quantity, String reason, String note) { }
    public record AdjustmentPayload(long partId, String quantityDelta, String reason) { }

    public static final class PartNotFound extends RuntimeException { }
    public static final class StockLimitExceeded extends RuntimeException { }
}
