package vn.garage.repair.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** Shared money calculation: round each line to whole VND before summing. */
public final class RepairTotals {
    public static final BigDecimal MAX_SAFE_AMOUNT = new BigDecimal("9007199254740991");

    private RepairTotals() { }

    public static Totals calculate(Collection<Line> lines) {
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal parts = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal amount = line.lineTotal();
            if (line.type() == Type.LABOR) labor = labor.add(amount);
            else parts = parts.add(amount);
        }
        BigDecimal total = labor.add(parts);
        if (labor.compareTo(MAX_SAFE_AMOUNT) > 0 || parts.compareTo(MAX_SAFE_AMOUNT) > 0
                || total.compareTo(MAX_SAFE_AMOUNT) > 0) throw new TotalLimitExceeded();
        return new Totals(labor, parts, total);
    }

    public record Line(Type type, BigDecimal quantity, BigDecimal unitPrice, boolean voided) {
        public Line {
            if (type == null || quantity == null || unitPrice == null || quantity.signum() < 0
                    || unitPrice.signum() < 0 || type == Type.LABOR && quantity.signum() <= 0
                    || voided && type != Type.LABOR
                    || quantity.scale() > 3 || quantity.precision() - quantity.scale() > 15
                    || quantity.precision() > 18 || unitPrice.scale() > 0
                    || unitPrice.precision() > 18 || unitPrice.compareTo(MAX_SAFE_AMOUNT) > 0) {
                throw new IllegalArgumentException("INVALID_REPAIR_LINE");
            }
        }
        public BigDecimal lineTotal() {
            if (voided || type == Type.PART && quantity.signum() == 0) return BigDecimal.ZERO;
            BigDecimal amount = quantity.multiply(unitPrice).setScale(0, RoundingMode.HALF_UP);
            if (amount.compareTo(MAX_SAFE_AMOUNT) > 0) throw new TotalLimitExceeded();
            return amount;
        }
    }

    public enum Type { LABOR, PART }
    public record Totals(BigDecimal laborAmount, BigDecimal partAmount, BigDecimal totalAmount) { }
    public static final class TotalLimitExceeded extends RuntimeException { }
}
