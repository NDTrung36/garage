package vn.garage.repair.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface RepairPartRepository {
    record PartItem(long id, long repairOrderId, long partId, String description, String unit,
                    BigDecimal quantity, BigDecimal unitPrice) {
        public BigDecimal lineTotal() {
            return quantity.multiply(unitPrice).setScale(0, java.math.RoundingMode.HALF_UP);
        }
    }
    record TotalLine(RepairTotals.Type type, BigDecimal quantity, BigDecimal unitPrice, boolean voided) { }

    Optional<PartItem> findPartItem(long repairOrderId, long partId);
    Optional<PartItem> findPartItemById(long repairOrderId, long itemId);
    PartItem insertPartItem(long repairOrderId, long partId, String description, String unit, long unitPrice);
    PartItem updatePartQuantity(long repairOrderId, long itemId, BigDecimal quantity);
    PartItem updatePartPrice(long repairOrderId, long itemId, long unitPrice);
    long incrementOrderVersion(long repairOrderId);
    List<PartItem> partItems(long repairOrderId);
    List<Long> partItemIds(long repairOrderId);
    List<TotalLine> totalLines(long repairOrderId);

    final class PartItemNotFound extends RuntimeException { }
    final class RepairOrderNotFound extends RuntimeException { }
}
