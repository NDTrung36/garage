package vn.garage.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact signed quantity using the SQL Server DECIMAL(18,3) domain. */
public record StockQuantity(BigDecimal value) implements Comparable<StockQuantity> {
    public static final BigDecimal MAX = new BigDecimal("999999999999999.999");
    private static final String UNSIGNED = "(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,3})?";
    private static final String SIGNED = "[+-]?" + UNSIGNED;

    public StockQuantity {
        if (value == null) throw new IllegalArgumentException("INVALID_STOCK_QUANTITY");
        try {
            value = value.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("INVALID_STOCK_QUANTITY", invalidScale);
        }
        if (value.abs().compareTo(MAX) > 0) throw new OutOfRange();
    }

    public static StockQuantity parsePositive(String raw) {
        StockQuantity parsed = parse(raw, UNSIGNED);
        if (parsed.isZero() || parsed.isNegative()) throw new IllegalArgumentException("INVALID_STOCK_QUANTITY");
        return parsed;
    }

    public static StockQuantity parseDelta(String raw) {
        StockQuantity parsed = parse(raw, SIGNED);
        if (parsed.isZero()) throw new IllegalArgumentException("INVALID_STOCK_DELTA");
        return parsed;
    }

    public static StockQuantity fromDatabase(BigDecimal value) {
        return new StockQuantity(value);
    }

    private static StockQuantity parse(String raw, String pattern) {
        if (raw == null || !raw.matches(pattern)) throw new IllegalArgumentException("INVALID_STOCK_QUANTITY");
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.abs().compareTo(MAX) > 0) throw new IllegalArgumentException("INVALID_STOCK_QUANTITY");
            return new StockQuantity(value);
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalArgumentException("INVALID_STOCK_QUANTITY", invalidNumber);
        }
    }

    public StockQuantity add(StockQuantity other) {
        return new StockQuantity(value.add(other.value));
    }

    public StockQuantity negate() {
        return new StockQuantity(value.negate());
    }

    public boolean isZero() { return value.signum() == 0; }
    public boolean isPositive() { return value.signum() > 0; }
    public boolean isNegative() { return value.signum() < 0; }
    public boolean isNonNegative() { return value.signum() >= 0; }
    public String toPlainString() { return value.toPlainString(); }

    @Override public int compareTo(StockQuantity other) { return value.compareTo(other.value); }

    public static final class OutOfRange extends RuntimeException {
        public OutOfRange() { super("STOCK_QUANTITY_OUT_OF_RANGE"); }
    }
}
