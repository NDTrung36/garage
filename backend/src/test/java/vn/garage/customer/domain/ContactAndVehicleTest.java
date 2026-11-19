package vn.garage.customer.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ContactAndVehicleTest {
    @Test
    void canonicalizesVietnameseNamePhoneAndPlate() {
        var contact = ContactAndVehicle.from("  Nguyễn　 Văn An  ", "+84 912 345 678", "MOTORBIKE",
                " 59a1 - 123.45 ", " Honda  Việt Nam ", " Wave   Alpha ");

        assertEquals("Nguyễn Văn An", contact.fullName());
        assertEquals("+84912345678", contact.phoneE164());
        assertEquals("59A1 - 123.45", contact.plateDisplay());
        assertEquals("59A112345", contact.plateNormalized());
        assertEquals("Honda Việt Nam", contact.brand());
        assertEquals("Wave Alpha", contact.model());
    }

    @Test
    void validatesNameByCodePointCountAtBothBounds() {
        assertEquals("An", CustomerContactRules.normalizeName(" An "));
        assertThrows(IllegalArgumentException.class, () -> CustomerContactRules.normalizeName("A"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeName("A".repeat(101)));
        assertEquals("😀A", CustomerContactRules.normalizeName("😀A"));
    }

    @Test
    void acceptsOnlyValidVietnameseMobileNumbers() {
        assertEquals("+84912345678", CustomerContactRules.normalizeVietnameseMobile("0912 345 678"));
        assertEquals("+84912345678", CustomerContactRules.normalizeVietnameseMobile("+84 912 345 678"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeVietnameseMobile("024 3822 1234"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeVietnameseMobile("0123456789"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeVietnameseMobile("0912345678 ext 12"));
    }

    @Test
    void validatesPlateAndOptionalVehicleFields() {
        assertEquals("A".repeat(20), CustomerContactRules.normalizePlate("A".repeat(20)));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizePlate("A".repeat(21)));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizePlate("30A-@123"));
        assertNull(CustomerContactRules.normalizeOptionalVehicleText("  "));
        assertEquals("Honda", CustomerContactRules.normalizeOptionalVehicleText(" Honda "));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerContactRules.normalizeOptionalVehicleText("x".repeat(101)));
    }
}
