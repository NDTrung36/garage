package vn.garage.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartTest {
    @Test
    void normalizesPartCodeAndTrimsVietnameseFields() {
        var part = new Part("  dau_nhot-01 ", "  Dầu nhớt  ", " Lít ", "BOTH", 0);

        assertEquals("DAU_NHOT-01", part.code());
        assertEquals("Dầu nhớt", part.name());
        assertEquals("Lít", part.unit());
        assertEquals(0, part.referencePrice());
    }

    @Test
    void rejectsInvalidCodesNamesUnitsTypesAndPrices() {
        assertEquals("INVALID_PART_CODE", assertThrows(IllegalArgumentException.class,
                () -> new Part("mã#", "Tên", "Cái", "CAR", 1)).getMessage());
        assertEquals("INVALID_PART_NAME", assertThrows(IllegalArgumentException.class,
                () -> new Part("PART", "  ", "Cái", "CAR", 1)).getMessage());
        assertEquals("INVALID_PART_UNIT", assertThrows(IllegalArgumentException.class,
                () -> new Part("PART", "Tên", " ".repeat(31), "CAR", 1)).getMessage());
        assertEquals("INVALID_VEHICLE_TYPE", assertThrows(IllegalArgumentException.class,
                () -> new Part("PART", "Tên", "Cái", "TRUCK", 1)).getMessage());
        assertEquals("INVALID_REFERENCE_PRICE", assertThrows(IllegalArgumentException.class,
                () -> new Part("PART", "Tên", "Cái", "CAR", -1)).getMessage());
    }
}
