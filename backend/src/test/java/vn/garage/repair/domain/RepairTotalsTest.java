package vn.garage.repair.domain;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepairTotalsTest {
    @Test
    void roundsEachLineHalfUpBeforeSumming() {
        var half = new RepairTotals.Line(RepairTotals.Type.PART, new BigDecimal("0.500"),
                new BigDecimal("101"), false);
        assertEquals(new BigDecimal("51"), half.lineTotal());
        assertEquals(new BigDecimal("102"), RepairTotals.calculate(List.of(half, half)).totalAmount());
    }

    @Test
    void zeroNetPartIsKeptButDoesNotAddToTotal() {
        var total = RepairTotals.calculate(List.of(
                new RepairTotals.Line(RepairTotals.Type.PART, new BigDecimal("0.000"), new BigDecimal("101"), false),
                new RepairTotals.Line(RepairTotals.Type.LABOR, new BigDecimal("1.000"), new BigDecimal("200"), false)));
        assertEquals(new BigDecimal("0"), total.partAmount());
        assertEquals(new BigDecimal("200"), total.totalAmount());
    }

    @Test
    void ignoresVoidedLaborAndRejectsUnsafeRepairTotal() {
        assertEquals(new BigDecimal("0"), RepairTotals.calculate(List.of(new RepairTotals.Line(
                RepairTotals.Type.LABOR, BigDecimal.ONE, new BigDecimal("500"), true))).totalAmount());
        assertThrows(RepairTotals.TotalLimitExceeded.class, () -> RepairTotals.calculate(List.of(
                new RepairTotals.Line(RepairTotals.Type.LABOR, new BigDecimal("2"),
                        RepairTotals.MAX_SAFE_AMOUNT, false))));
        assertThrows(IllegalArgumentException.class, () -> new RepairTotals.Line(RepairTotals.Type.LABOR,
                BigDecimal.ONE, RepairTotals.MAX_SAFE_AMOUNT.add(BigDecimal.ONE), false));
    }

    @Test
    void validatesSqlDecimalScaleRangeAndLaborQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new RepairTotals.Line(RepairTotals.Type.LABOR,
                new BigDecimal("1.0000"), BigDecimal.ONE, false));
        assertThrows(IllegalArgumentException.class, () -> new RepairTotals.Line(RepairTotals.Type.LABOR,
                BigDecimal.ZERO, BigDecimal.ONE, false));
        assertThrows(IllegalArgumentException.class, () -> new RepairTotals.Line(RepairTotals.Type.LABOR,
                new BigDecimal("-0.001"), BigDecimal.ONE, false));
        assertEquals(BigDecimal.ZERO, RepairTotals.calculate(List.of(new RepairTotals.Line(
                RepairTotals.Type.LABOR, new BigDecimal("0.500"), BigDecimal.ZERO, false))).totalAmount());
    }
}
