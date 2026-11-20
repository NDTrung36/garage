package vn.garage.repair.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import vn.garage.repair.domain.BookingStore;

@Service
public class StaffBookingQuery {
    private static final ZoneId LOCAL_ZONE = ZoneId.of(SlotGenerator.TIME_ZONE);
    private final BookingStore bookings;
    private final Clock clock;

    public StaffBookingQuery(BookingStore bookings, Clock clock) { this.bookings = bookings; this.clock = clock; }

    public BookingStore.BookingPage list(LocalDate date, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new InvalidBookingQuery();
        LocalDate localDate = date == null ? LocalDate.now(clock.withZone(LOCAL_ZONE)) : date;
        BookingStore.BookingStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try { parsed = BookingStore.BookingStatus.valueOf(status); }
            catch (IllegalArgumentException invalid) { throw new InvalidBookingQuery(); }
        }
        return bookings.list(localDate.atStartOfDay(LOCAL_ZONE).toOffsetDateTime(),
                localDate.plusDays(1).atStartOfDay(LOCAL_ZONE).toOffsetDateTime(), parsed, page, size);
    }

    public static final class InvalidBookingQuery extends RuntimeException { }
}
