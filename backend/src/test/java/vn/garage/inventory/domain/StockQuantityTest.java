package vn.garage.inventory.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StockQuantityTest {
    @Test
    void parsesAndAddsScaleThreeValuesExactly() {
        var opening = StockQuantity.parsePositive("2.500");
        var down = StockQuantity.parseDelta("-0.750");
        var up = StockQuantity.parseDelta("+0.250");

        assertEquals("2.500", opening.toPlainString());
        assertEquals("1.750", opening.add(down).toPlainString());
        assertEquals("2.000", opening.add(down).add(up).toPlainString());
    }

    @Test
    void parsesBoundaryAndRejectsInvalidWireQuantities() {
        assertEquals("999999999999999.999", StockQuantity.parsePositive("999999999999999.999").toPlainString());
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parsePositive("0"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parsePositive("-1.000"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parseDelta("0.000"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parseDelta("1.0000"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parsePositive("1e3"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parsePositive("1000000000000000.000"));
        assertThrows(IllegalArgumentException.class, () -> StockQuantity.parsePositive("01.000"));
    }

    @Test
    void arithmeticCanDetectUnderflowAndOverflowWithoutRounding() {
        var zero = new StockQuantity(BigDecimal.ZERO);
        var maximum = StockQuantity.fromDatabase(StockQuantity.MAX);

        assertEquals("-0.001", zero.add(StockQuantity.parseDelta("-0.001")).toPlainString());
        assertThrows(StockQuantity.OutOfRange.class,
                () -> maximum.add(StockQuantity.parseDelta("+0.001")));
        assertThrows(IllegalArgumentException.class,
                () -> new StockQuantity(new BigDecimal("1.0001")));
    }
}
