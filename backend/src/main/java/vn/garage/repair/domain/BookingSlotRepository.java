package vn.garage.repair.domain;

import java.time.OffsetDateTime;

public interface BookingSlotRepository {
    LockedSlot lockForBooking(long slotId);
    boolean plateAlreadyBooked(long slotId, String normalizedPlate);

    record LockedSlot(long id, String vehicleType, OffsetDateTime startsAt, OffsetDateTime endsAt,
                      int capacity, int used, boolean closed, int minimumLeadMinutes,
                      int maximumAdvanceDays) { }
}
