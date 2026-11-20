package vn.garage.repair.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.repair.domain.BookingSlotRepository;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@Service
public class VehicleIntakeService {
    private static final String BOOKING_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BOOKING_CODE_ATTEMPTS = 3;
    private final BookingStore bookings;
    private final BookingSlotRepository slots;
    private final RepairOrderStore orders;
    private final SqlServerWriteTransactions writes;
    private final Clock clock;
    private final IdempotencyExecutor idempotency;
    private final CustomerVehicleOnboarding customers;
    private final BookingServiceCatalog services;
    private final Supplier<String> bookingCodeGenerator;

    @Autowired
    public VehicleIntakeService(BookingStore bookings, BookingSlotRepository slots, RepairOrderStore orders,
                                SqlServerWriteTransactions writes, Clock clock, IdempotencyExecutor idempotency,
                                CustomerVehicleOnboarding customers, BookingServiceCatalog services) {
        this(bookings, slots, orders, writes, clock, idempotency, customers, services,
                VehicleIntakeService::newBookingCode);
    }

    VehicleIntakeService(BookingStore bookings, BookingSlotRepository slots, RepairOrderStore orders,
                         SqlServerWriteTransactions writes, Clock clock, IdempotencyExecutor idempotency,
                         CustomerVehicleOnboarding customers, BookingServiceCatalog services,
                         Supplier<String> bookingCodeGenerator) {
        this.bookings = bookings; this.slots = slots; this.orders = orders; this.writes = writes; this.clock = clock;
        this.idempotency = idempotency; this.customers = customers; this.services = services;
        this.bookingCodeGenerator = bookingCodeGenerator;
    }

    /** Test and existing check-in callers that do not invoke walk-in. */
    public VehicleIntakeService(BookingStore bookings, BookingSlotRepository slots, RepairOrderStore orders,
                                SqlServerWriteTransactions writes, Clock clock) {
        this(bookings, slots, orders, writes, clock, null, null, null, null);
    }

    public CheckInResult checkIn(long bookingId, long expectedVersion, Integer odometerKm,
                                 String conditionNotes, String internalNotes, long actorId) {
        if (bookingId <= 0 || expectedVersion < 0 || actorId <= 0 || odometerKm != null && odometerKm < 0
                || tooLong(conditionNotes) || tooLong(internalNotes)) throw new InvalidIntakeInput();
        return writes.execute(() -> checkInInTransaction(bookingId, expectedVersion, odometerKm,
                clean(conditionNotes), clean(internalNotes), actorId));
    }

    public IdempotencyExecutor.Result<CheckInResult> walkIn(WalkInCommand command, String key, long actorId) {
        if (command == null || actorId <= 0 || idempotency == null || customers == null || services == null) {
            throw new InvalidIntakeInput();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return idempotency.execute("repair.walkin.create", actorId, key, command,
                        CheckInResult.class, () -> new IdempotencyExecutor.Response<>(201,
                                createWalkInInTransaction(command, actorId)));
            } catch (BookingStore.BookingCodeCollisionException collision) {
                if (attempt >= BOOKING_CODE_ATTEMPTS) throw new BookingCodeRetriesExhausted(collision);
                // IdempotencyExecutor has rolled the failed transaction back before this new attempt.
            }
        }
    }

    private CheckInResult createWalkInInTransaction(WalkInCommand command, long actorId) {
        List<BookingServiceCatalog.ServiceSnapshot> selected = services.activeForBooking(
                command.serviceIds(), command.contact().vehicleType());
        var customerVehicle = customers.obtain(command.contact());
        ContactAndVehicle snapshot = customerVehicle.snapshot();
        String code = bookingCodeGenerator.get();
        long bookingId;
        bookingId = bookings.createWalkIn(new BookingStore.NewWalkInBooking(code,
                customerVehicle.customerId(), customerVehicle.vehicleId(), actorId,
                snapshot.vehicleType(), snapshot.fullName(), snapshot.phoneE164(), snapshot.plateDisplay(),
                snapshot.plateNormalized(), snapshot.brand(), snapshot.model(), command.note()),
                selected.stream().map(service -> new BookingStore.RequestedService(Long.toString(service.id()),
                        service.name(), service.referencePrice())).toList());
        OffsetDateTime receivedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        RepairOrderStore.RepairOrder order = orders.create(new RepairOrderStore.NewRepairOrder(bookingId,
                actorId, receivedAt, command.odometerKm(), command.conditionNotes(), command.internalNotes()));
        var booking = new BookingStore.LockedBooking(bookingId, code, "WALK_IN",
                BookingStore.BookingStatus.CHECKED_IN, null, 0);
        return result(booking, order);
    }

    private static String newBookingCode() {
        StringBuilder code = new StringBuilder("BK-");
        for (int index = 0; index < 12; index++) code.append(BOOKING_CODE_ALPHABET.charAt(RANDOM.nextInt(BOOKING_CODE_ALPHABET.length())));
        return code.toString();
    }

    private CheckInResult checkInInTransaction(long id, long expectedVersion, Integer km,
                                                String condition, String internal, long actor) {
        BookingStore.BookingRef hint = bookings.readCheckInHint(id).orElseThrow(BookingNotFound::new);
        if (hint.slotId() != null) slots.lockForBooking(hint.slotId()); // Deliberately ignore closed, time and capacity.
        BookingStore.LockedBooking booking = bookings.lockForCheckIn(id).orElseThrow(BookingNotFound::new);
        if (booking.status() == BookingStore.BookingStatus.CHECKED_IN
                || booking.status() == BookingStore.BookingStatus.COMPLETED) {
            RepairOrderStore.RepairOrder existing = orders.findByBookingForUpdate(id)
                    .orElseThrow(RepairInvariantViolation::new);
            return result(booking, existing);
        }
        if (booking.status() != BookingStore.BookingStatus.CONFIRMED)
            throw new BookingStateConflict();
        if (!"ONLINE".equals(booking.source()) || booking.slotId() == null)
            throw new BookingStateConflict();
        if (!java.util.Objects.equals(hint.slotId(), booking.slotId())) throw new StaleBookingVersion();
        if (booking.version() != expectedVersion) throw new StaleBookingVersion();
        if (orders.findByBookingForUpdate(id).isPresent()) throw new RepairInvariantViolation();
        OffsetDateTime receivedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        RepairOrderStore.RepairOrder created = orders.create(new RepairOrderStore.NewRepairOrder(id, actor,
                receivedAt, km, condition, internal));
        if (!bookings.markCheckedIn(id, expectedVersion, receivedAt)) throw new StaleBookingVersion();
        BookingStore.LockedBooking checkedIn = new BookingStore.LockedBooking(booking.id(), booking.code(),
                booking.source(), BookingStore.BookingStatus.CHECKED_IN, booking.slotId(), booking.version() + 1);
        return result(checkedIn, created);
    }

    private static CheckInResult result(BookingStore.LockedBooking booking, RepairOrderStore.RepairOrder order) {
        return new CheckInResult(Long.toString(booking.id()), booking.code(), booking.status(), booking.version(),
                Long.toString(order.id()), order.status(), order.version(), order.receivedAt());
    }

    private static boolean tooLong(String value) { return value != null && value.length() > 2000; }
    private static String clean(String value) { if (value == null) return null; String result = value.strip(); return result.isEmpty() ? null : result; }

    public record CheckInResult(String bookingId, String bookingCode, BookingStore.BookingStatus bookingStatus,
                                long bookingVersion, String repairOrderId, RepairStatus repairStatus,
                                long repairOrderVersion, OffsetDateTime receivedAt) { }

    public record WalkInCommand(ContactAndVehicle contact, List<Long> serviceIds, String note,
                                Integer odometerKm, String conditionNotes, String internalNotes) {
        public WalkInCommand {
            if (contact == null || serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > 50
                    || serviceIds.stream().anyMatch(id -> id == null || id <= 0)
                    || serviceIds.stream().distinct().count() != serviceIds.size()
                    || note != null && note.length() > 1000 || odometerKm != null && odometerKm < 0
                    || tooLong(conditionNotes) || tooLong(internalNotes)) throw new InvalidIntakeInput();
            serviceIds = serviceIds.stream().sorted().toList();
            note = clean(note);
            conditionNotes = clean(conditionNotes);
            internalNotes = clean(internalNotes);
        }
    }

    public static final class InvalidIntakeInput extends RuntimeException { }
    public static final class BookingNotFound extends RuntimeException { }
    public static final class BookingStateConflict extends RuntimeException { }
    public static final class StaleBookingVersion extends RuntimeException { }
    public static final class RepairInvariantViolation extends RuntimeException { }
    public static final class BookingCodeRetriesExhausted extends RuntimeException {
        public BookingCodeRetriesExhausted(Throwable cause) { super(cause); }
    }
}
