package vn.garage.repair.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence contract for LABOR rows. Callers lock the owning repair order first. */
public interface RepairLaborRepository {
    Optional<LaborItem> findLaborForUpdate(long repairOrderId, long itemId);

    List<LaborItem> laborItems(long repairOrderId);

    LaborItem insertLabor(long repairOrderId, Long serviceId, String description, String unit,
                          BigDecimal quantity, long unitPrice);

    LaborItem updateLabor(long repairOrderId, long itemId, String description,
                          BigDecimal quantity, long unitPrice);

    boolean voidLabor(long repairOrderId, long itemId, long actorId, OffsetDateTime voidedAt);

    record LaborItem(long id, long repairOrderId, Long serviceId, String description, String unit,
                     BigDecimal quantity, BigDecimal unitPrice, boolean voided) {
        public BigDecimal lineTotal() {
            return new RepairTotals.Line(RepairTotals.Type.LABOR, quantity, unitPrice, voided).lineTotal();
        }
    }

    final class LaborItemNotFound extends RuntimeException { }
    final class LaborItemVoided extends RuntimeException { }
}
