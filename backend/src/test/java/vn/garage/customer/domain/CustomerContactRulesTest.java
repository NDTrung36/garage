package vn.garage.customer.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CustomerContactRulesTest {
    @Test void canonicalizesVietnameseMobileNumbers() {
        assertEquals("+84912345678", CustomerContactRules.normalizeVietnameseMobile(" 0912 345 678 "));
        assertEquals("+84912345678", CustomerContactRules.normalizeVietnameseMobile("+84 912 345 678"));
    }

    @Test void rejectsInvalidVietnameseNumber() {
        assertEquals("INVALID_PHONE", assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeVietnameseMobile("0123456789")).getMessage());
    }

    @Test void normalizesNameAndPlateWithoutLosingVietnameseName() {
        assertEquals("Nguyễn Văn An", CustomerContactRules.normalizeName("  Nguyễn   Văn An  "));
        assertEquals("59A112345", CustomerContactRules.normalizePlate("59a1-123.45"));
        assertThrows(IllegalArgumentException.class, () -> CustomerContactRules.normalizePlate("59A1-@123"));
    }
}
