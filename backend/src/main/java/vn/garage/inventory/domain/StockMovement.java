package vn.garage.inventory.domain;

import java.time.OffsetDateTime;

public record StockMovement(long id, long partId, String partCode, Type type,
                            StockQuantity quantityDelta, StockQuantity quantityBefore,
                            StockQuantity quantityAfter, String reason, String note,
                            long actorId, OffsetDateTime createdAt,
                            Long repairItemId, Long originalIssueId) {
    public enum Type { RECEIPT, ISSUE, RETURN, ADJUSTMENT }

    public StockMovement {
        if (id < 1 || partId < 1 || actorId < 1 || partCode == null || type == null
                || quantityDelta == null || quantityBefore == null || quantityAfter == null || createdAt == null) {
            throw new IllegalArgumentException("INVALID_STOCK_MOVEMENT");
        }
        reason = reason == null ? "" : reason.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("INVALID_STOCK_REASON");
        if (quantityBefore.isNegative() || quantityAfter.isNegative() || quantityDelta.isZero()
                || !quantityBefore.add(quantityDelta).equals(quantityAfter)) {
            throw new IllegalArgumentException("INVALID_STOCK_BALANCE");
        }
        switch (type) {
            case RECEIPT -> {
                if (!quantityDelta.isPositive() || repairItemId != null || originalIssueId != null
                        || note != null && note.length() > 500) throw new IllegalArgumentException("INVALID_STOCK_MOVEMENT_LINKS");
            }
            case ADJUSTMENT -> {
                if (repairItemId != null || originalIssueId != null || note != null) throw new IllegalArgumentException("INVALID_STOCK_MOVEMENT_LINKS");
            }
            case ISSUE -> {
                if (!quantityDelta.isNegative() || repairItemId == null || originalIssueId != null || note != null)
                    throw new IllegalArgumentException("INVALID_STOCK_MOVEMENT_LINKS");
            }
            case RETURN -> {
                if (!quantityDelta.isPositive() || repairItemId == null || originalIssueId == null || note != null)
                    throw new IllegalArgumentException("INVALID_STOCK_MOVEMENT_LINKS");
            }
        }
    }
}
