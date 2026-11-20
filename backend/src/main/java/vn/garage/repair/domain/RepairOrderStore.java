package vn.garage.repair.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RepairOrderStore {
    Optional<RepairOrder> findByBookingForUpdate(long bookingId);
    Optional<RepairOrderContext> findByIdForUpdate(long repairOrderId);
    Optional<RepairOrderContext> findById(long repairOrderId);

    Optional<RepairOrderData> findDetails(long repairOrderId);

    Optional<Long> findBookingId(long repairOrderId);

    List<RepairOrderData> list(RepairStatus status, int page, int size);

    long count(RepairStatus status);

    boolean updateHeader(long repairOrderId, long expectedVersion,
                         boolean odometerPresent, Integer odometerKm,
                         boolean conditionNotesPresent, String conditionNotes,
                         boolean internalNotesPresent, String internalNotes);

    RepairOrder create(NewRepairOrder order);

    boolean markCompleted(long repairOrderId, long expectedVersion, long actorId, OffsetDateTime completedAt);

    record NewRepairOrder(long bookingId, long receivedBy, OffsetDateTime receivedAt,
                          Integer odometerKm, String conditionNotes, String internalNotes) { }

    record RepairOrder(long id, long bookingId, RepairStatus status, long version,
                       long receivedBy, OffsetDateTime receivedAt, Integer odometerKm,
                       String conditionNotes, String internalNotes) { }
    record RepairOrderContext(long id, RepairStatus status, long version, String vehicleType) { }
    record RepairOrderData(long id, long bookingId, String bookingCode, String bookingSource,
                           BookingStore.BookingStatus bookingStatus, RepairStatus status, long version,
                           Integer odometerKm, String conditionNotes, String internalNotes,
                           long receivedBy, OffsetDateTime receivedAt, Long completedBy,
                           OffsetDateTime completedAt, String vehicleType, String contactName,
                           String contactPhone, String plate, String brand, String model) { }
}
