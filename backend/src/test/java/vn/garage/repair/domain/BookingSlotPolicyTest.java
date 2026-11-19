package vn.garage.repair.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class BookingSlotPolicyTest {
    private final OffsetDateTime now = OffsetDateTime.parse("2031-01-06T08:00:00+07:00");
    private final OffsetDateTime start = OffsetDateTime.parse("2031-01-06T09:00:00+07:00");

    @Test void permitsAvailableSlotAtTheMinimumLeadBoundary() {
        assertDoesNotThrow(() -> BookingSlotPolicy.validate(false, "CAR", "CAR", 2, 1,
                start, now, 60, 14));
    }
    @Test void rejectsClosedFullWrongTypeAndOutOfWindowSlots() {
        assertCode("SLOT_UNAVAILABLE", true, "CAR", "CAR", 2, 0, start, 60, 14);
        assertCode("SLOT_UNAVAILABLE", false, "MOTORBIKE", "CAR", 2, 0, start, 60, 14);
        assertCode("SLOT_FULL", false, "CAR", "CAR", 1, 1, start, 60, 14);
        assertCode("SLOT_UNAVAILABLE", false, "CAR", "CAR", 2, 0, start, 61, 14);
        assertCode("SLOT_UNAVAILABLE", false, "CAR", "CAR", 2, 0,
                OffsetDateTime.parse("2031-01-21T09:00:00+07:00"), 1, 14);
    }
    private void assertCode(String expected, boolean closed, String slotType, String requestedType,
            int capacity, int used, OffsetDateTime startsAt, int lead, int days) {
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> BookingSlotPolicy.validate(
                closed, slotType, requestedType, capacity, used, startsAt, now, lead, days)).getMessage());
    }
}
