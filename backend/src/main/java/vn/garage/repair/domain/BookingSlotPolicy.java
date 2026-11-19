package vn.garage.repair.domain;

import java.time.OffsetDateTime;

public final class BookingSlotPolicy {
    private BookingSlotPolicy() { }

    public static void validate(boolean closed, String slotVehicleType, String requestedType,
            int capacity, int used, OffsetDateTime startsAt, OffsetDateTime now,
            int minimumLeadMinutes, int maximumAdvanceDays) {
        if (!requestedType.equals(slotVehicleType)) throw new IllegalArgumentException("SLOT_UNAVAILABLE");
        if (closed || startsAt.isBefore(now.plusMinutes(minimumLeadMinutes))) {
            throw new IllegalArgumentException("SLOT_UNAVAILABLE");
        }
        if (startsAt.toLocalDate().isAfter(now.toLocalDate().plusDays(maximumAdvanceDays))) {
            throw new IllegalArgumentException("SLOT_UNAVAILABLE");
        }
        if (used >= capacity) throw new IllegalArgumentException("SLOT_FULL");
    }
}
