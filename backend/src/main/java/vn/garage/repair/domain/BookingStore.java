package vn.garage.repair.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingStore {
    Optional<BookingRef> readCheckInHint(long bookingId);

    Optional<LockedBooking> lockForCheckIn(long bookingId);

    boolean markCheckedIn(long bookingId, long expectedVersion, OffsetDateTime updatedAt);

    boolean markCompleted(long bookingId, long expectedVersion, OffsetDateTime updatedAt);

    long createWalkIn(NewWalkInBooking booking, List<RequestedService> services);

    List<RequestedService> requestedServicesForBooking(long bookingId);

    BookingPage list(OffsetDateTime fromInclusive, OffsetDateTime toExclusive,
                     BookingStatus status, int page, int size);

    enum BookingStatus { CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW }

    record BookingRef(long bookingId, Long slotId) { }

    record NewWalkInBooking(String code, long customerId, long vehicleId, long createdBy,
                            String vehicleType, String contactName, String contactPhone,
                            String plate, String normalizedPlate, String brand, String model, String note) { }

    final class BookingCodeCollisionException extends RuntimeException {
        public BookingCodeCollisionException(Throwable cause) { super(cause); }
    }

    record LockedBooking(long id, String code, String source, BookingStatus status,
                         Long slotId, long version) { }

    record RequestedService(String id, String name, long referencePrice) { }

    record BookingSummary(String id, String code, String source, BookingStatus status, long version,
                          String slotId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          OffsetDateTime receivedAt, String vehicleType, String contactName,
                          String contactPhone, String plate, String brand, String model,
                          List<RequestedService> requestedServices, String repairOrderId,
                          RepairStatus repairStatus) { }

    record BookingPage(List<BookingSummary> items, int page, int size,
                       long totalElements, long totalPages) { }
}
