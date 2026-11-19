package vn.garage.repair.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.repair.domain.BookingSlotPolicy;
import vn.garage.repair.domain.BookingSlotRepository;
import vn.garage.repair.domain.GuestBookingRequest;
import vn.garage.support.idempotency.IdempotencyExecutor;

@Service
public class GuestBookingService {
    private static final String SCOPE = "public.booking.create";
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final long JS_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final ZoneId GARAGE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final IdempotencyExecutor idempotency;
    private final BookingSlotRepository slots;
    private final BookingServiceCatalog services;
    private final CustomerVehicleOnboarding customers;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public GuestBookingService(JdbcTemplate db, IdempotencyExecutor idempotency, BookingSlotRepository slots,
            BookingServiceCatalog services, CustomerVehicleOnboarding customers, ObjectMapper json,
            Clock clock) {
        this.db = db; this.idempotency = idempotency; this.slots = slots;
        this.services = services; this.customers = customers; this.json = json; this.clock = clock;
    }

    public GuestBookingService(JdbcTemplate db, IdempotencyExecutor idempotency, BookingSlotRepository slots,
            BookingServiceCatalog services, CustomerVehicleOnboarding customers, Clock clock) {
        this(db, idempotency, slots, services, customers, new ObjectMapper(), clock);
    }

    public BookingResult create(GuestBookingRequest request, String requestKey) {
        String hash = IdempotencyExecutor.hash(json, request);
        try {
            return idempotency.executeWithHash(SCOPE, requestKey, hash, BookingResult.class,
                    () -> new IdempotencyExecutor.Response<>(201, createBooking(request))).body();
        } catch (IdempotencyExecutor.ConflictException conflict) {
            throw new IdempotencyConflictException(conflict);
        } catch (vn.garage.support.transaction.SqlServerWriteTransactions.LockTimeoutException
                 | vn.garage.support.transaction.SqlServerWriteTransactions.DeadlockRetriesExhaustedException timeout) {
            throw new RequestInProgressException(timeout);
        }
    }

    private BookingResult createBooking(GuestBookingRequest request) {
            var slot = slots.lockForBooking(request.slotId());
            OffsetDateTime now = OffsetDateTime.now(clock.withZone(GARAGE_ZONE));
            BookingSlotPolicy.validate(slot.closed(), slot.vehicleType(), request.vehicleType(),
                    slot.capacity(), 0, slot.startsAt(), now, slot.minimumLeadMinutes(), slot.maximumAdvanceDays());
            if (slots.plateAlreadyBooked(slot.id(), request.plateNormalized())) {
                throw new BookingConflictException();
            }
            if (slot.used() >= slot.capacity()) throw new SlotFullException();

            List<BookingServiceCatalog.ServiceSnapshot> selected = services.activeForBooking(
                    request.serviceIds(), request.vehicleType());
            long total = selected.stream().mapToLong(BookingServiceCatalog.ServiceSnapshot::referencePrice)
                    .reduce(0L, Math::addExact);
            if (total > JS_SAFE_INTEGER) throw new IllegalArgumentException("BOOKING_TOTAL_TOO_LARGE");

            ContactAndVehicle snapshot = ContactAndVehicle.from(request.fullName(), request.phoneE164(),
                    request.vehicleType(), request.plateDisplay(), request.brand(), request.model());
            var customerVehicle = customers.obtain(snapshot);
            snapshot = customerVehicle.snapshot();
            String code = newBookingCode();
            Long bookingId = db.queryForObject("""
                    INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                        contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,
                        brand_snapshot,model_snapshot,customer_note,created_by)
                    OUTPUT INSERTED.id VALUES(?,?,?,?,'ONLINE','CONFIRMED',?,?,?,?,?,?,?,?,NULL)
                    """, Long.class, code, customerVehicle.customerId(), customerVehicle.vehicleId(), slot.id(),
                    snapshot.vehicleType(), snapshot.fullName(), snapshot.phoneE164(), snapshot.plateDisplay(),
                    snapshot.plateNormalized(), snapshot.brand(), snapshot.model(), request.note());
            for (var service : selected) {
                db.update("""
                        INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot)
                        VALUES(?,?,?,?)
                        """, bookingId, service.id(), service.name(), service.referencePrice());
            }
            var result = new BookingResult(code, "CONFIRMED", format(slot.startsAt()), format(slot.endsAt()),
                    request.vehicleType(), selected.stream().map(s -> new BookedService(s.name(), s.referencePrice())).toList(), total);
            return result;
    }

    private static String newBookingCode() {
        StringBuilder value = new StringBuilder("BK-");
        for (int i = 0; i < 12; i++) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
    }

    public record BookedService(String name, long referencePrice) { }
    public record BookingResult(String bookingCode, String status, String startsAt, String endsAt,
                                String vehicleType, List<BookedService> services, long referenceTotal) { }
    public static class BookingConflictException extends RuntimeException { }
    public static class SlotFullException extends RuntimeException { }
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(Throwable cause) { super(cause); }
    }
    public static class RequestInProgressException extends RuntimeException {
        public RequestInProgressException(Throwable cause) { super(cause); }
    }
}
