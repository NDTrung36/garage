package vn.garage.repair.infrastructure;

import java.sql.Types;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairStatus;

@Repository
public class JdbcBookingStore implements BookingStore {
    private final JdbcTemplate db;

    public JdbcBookingStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public Optional<BookingRef> readCheckInHint(long bookingId) {
        List<BookingRef> rows = db.query("""
                SELECT id,slot_id FROM dbo.bookings WHERE id=?
                """, (rs, row) -> new BookingRef(rs.getLong("id"), rs.getObject("slot_id", Long.class)), bookingId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<LockedBooking> lockForCheckIn(long bookingId) {
        List<LockedBooking> rows = db.query("""
                SELECT id,code,source,status,slot_id,version
                FROM dbo.bookings WITH (UPDLOCK,HOLDLOCK,INDEX(PK_bookings))
                WHERE id=?
                """, (rs, row) -> new LockedBooking(rs.getLong("id"), rs.getString("code"),
                rs.getString("source"), BookingStatus.valueOf(rs.getString("status")),
                rs.getObject("slot_id", Long.class), rs.getLong("version")), bookingId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean markCheckedIn(long bookingId, long expectedVersion, OffsetDateTime updatedAt) {
        return db.update("""
                UPDATE dbo.bookings SET status='CHECKED_IN',version=version+1,updated_at=?
                WHERE id=? AND status='CONFIRMED' AND version=?
                """, updatedAt, bookingId, expectedVersion) == 1;
    }

    @Override
    public boolean markCompleted(long bookingId, long expectedVersion, OffsetDateTime updatedAt) {
        return db.update("""
                UPDATE dbo.bookings SET status='COMPLETED',version=version+1,updated_at=?
                WHERE id=? AND status='CHECKED_IN' AND version=?
                """, updatedAt, bookingId, expectedVersion) == 1;
    }

    @Override
    public long createWalkIn(NewWalkInBooking booking, List<RequestedService> services) {
        final long bookingId;
        try {
            bookingId = db.queryForObject("""
                    INSERT dbo.bookings(code,customer_id,vehicle_id,slot_id,source,status,vehicle_type_snapshot,
                        contact_name_snapshot,contact_phone_snapshot,plate_snapshot,plate_normalized_snapshot,
                        brand_snapshot,model_snapshot,customer_note,created_by)
                    OUTPUT INSERTED.id VALUES(?,?,?,NULL,'WALK_IN','CHECKED_IN',?,?,?,?,?,?,?,?,?)
                    """, Long.class, booking.code(), booking.customerId(), booking.vehicleId(), booking.vehicleType(),
                    booking.contactName(), booking.contactPhone(), booking.plate(), booking.normalizedPlate(),
                    booking.brand(), booking.model(), booking.note(), booking.createdBy());
        } catch (DuplicateKeyException conflict) {
            if (isBookingCodeCollision(conflict)) throw new BookingStore.BookingCodeCollisionException(conflict);
            throw conflict;
        }
        for (RequestedService service : services) {
            db.update("""
                    INSERT dbo.booking_services(booking_id,service_id,service_name_snapshot,reference_price_snapshot)
                    VALUES(?,?,?,?)
                    """, bookingId, Long.parseLong(service.id()), service.name(), service.referencePrice());
        }
        return bookingId;
    }

    @Override
    public List<RequestedService> requestedServicesForBooking(long bookingId) {
        return db.query("""
                SELECT service_id,service_name_snapshot,reference_price_snapshot
                FROM dbo.booking_services WHERE booking_id=? ORDER BY id
                """, (rs, row) -> new RequestedService(Long.toString(rs.getLong("service_id")),
                rs.getString("service_name_snapshot"), rs.getLong("reference_price_snapshot")), bookingId);
    }

    private static boolean isBookingCodeCollision(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            String message = current.getMessage();
            if (message != null && message.contains("UX_bookings_code")) return true;
            if (current instanceof SQLException sql) {
                for (SQLException item = sql; item != null; item = item.getNextException()) {
                    if ((item.getErrorCode() == 2601 || item.getErrorCode() == 2627)
                            && item.getMessage() != null && item.getMessage().contains("UX_bookings_code")) return true;
                }
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return false;
    }

    @Override
    public BookingPage list(OffsetDateTime fromInclusive, OffsetDateTime toExclusive,
                            BookingStatus status, int page, int size) {
        String statusClause = status == null ? "" : " AND b.status=?";
        String fromClause = """
                ((b.slot_id IS NOT NULL AND s.starts_at>=? AND s.starts_at<?)
                 OR (b.slot_id IS NULL AND ro.received_at>=? AND ro.received_at<?))
                """ + statusClause;
        long total = queryCount(fromClause, fromInclusive, toExclusive, status);
        List<BookingSummary> bookings = db.query(connection -> {
            String sql = """
                    SELECT b.id,b.code,b.source,b.status,b.version,b.slot_id,
                           s.starts_at,s.ends_at,ro.id repair_order_id,ro.status repair_status,
                           ro.received_at,b.vehicle_type_snapshot,b.contact_name_snapshot,
                           b.contact_phone_snapshot,b.plate_snapshot,b.brand_snapshot,b.model_snapshot,
                           COALESCE(s.starts_at,ro.received_at) event_at
                    FROM dbo.bookings b
                    LEFT JOIN dbo.appointment_slots s ON s.id=b.slot_id
                    LEFT JOIN dbo.repair_orders ro ON ro.booking_id=b.id
                    WHERE %s
                    ORDER BY event_at DESC,b.id DESC
                    OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                    """.formatted(fromClause);
            var statement = connection.prepareStatement(sql);
            int parameter = 1;
            statement.setObject(parameter++, fromInclusive);
            statement.setObject(parameter++, toExclusive);
            statement.setObject(parameter++, fromInclusive);
            statement.setObject(parameter++, toExclusive);
            if (status != null) statement.setObject(parameter++, status.name(), Types.VARCHAR);
            statement.setLong(parameter++, (long) page * size);
            statement.setInt(parameter, size);
            statement.setQueryTimeout(10);
            return statement;
        }, (rs, row) -> new BookingSummary(Long.toString(rs.getLong("id")), rs.getString("code"),
                rs.getString("source"), BookingStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                stringId(rs.getObject("slot_id", Long.class)), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("vehicle_type_snapshot"), rs.getString("contact_name_snapshot"),
                rs.getString("contact_phone_snapshot"), rs.getString("plate_snapshot"),
                rs.getString("brand_snapshot"), rs.getString("model_snapshot"), List.of(),
                stringId(rs.getObject("repair_order_id", Long.class)), repairStatus(rs.getString("repair_status"))));
        if (bookings.isEmpty()) return new BookingPage(List.of(), page, size, total, Math.ceilDiv(total, size));
        List<Long> ids = bookings.stream().map(item -> Long.parseLong(item.id())).toList();
        Map<Long, List<RequestedService>> requested = requestedServices(ids);
        List<BookingSummary> enriched = bookings.stream().map(item -> new BookingSummary(item.id(), item.code(),
                item.source(), item.status(), item.version(), item.slotId(), item.startsAt(), item.endsAt(),
                item.receivedAt(), item.vehicleType(), item.contactName(), item.contactPhone(), item.plate(),
                item.brand(), item.model(), requested.getOrDefault(Long.parseLong(item.id()), List.of()),
                item.repairOrderId(), item.repairStatus())).toList();
        return new BookingPage(enriched, page, size, total, Math.ceilDiv(total, size));
    }

    private long queryCount(String where, OffsetDateTime fromInclusive, OffsetDateTime toExclusive,
                            BookingStatus status) {
        return db.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT COUNT_BIG(*)
                    FROM dbo.bookings b
                    LEFT JOIN dbo.appointment_slots s ON s.id=b.slot_id
                    LEFT JOIN dbo.repair_orders ro ON ro.booking_id=b.id
                    WHERE %s
                    """.formatted(where));
            statement.setObject(1, fromInclusive);
            statement.setObject(2, toExclusive);
            statement.setObject(3, fromInclusive);
            statement.setObject(4, toExclusive);
            if (status != null) statement.setObject(5, status.name(), Types.VARCHAR);
            statement.setQueryTimeout(10);
            return statement;
        }, rs -> rs.next() ? rs.getLong(1) : 0L);
    }

    private Map<Long, List<RequestedService>> requestedServices(List<Long> bookingIds) {
        String marks = String.join(",", java.util.Collections.nCopies(bookingIds.size(), "?"));
        Map<Long, List<RequestedService>> result = new HashMap<>();
        db.query("""
                SELECT booking_id,service_id,service_name_snapshot,reference_price_snapshot
                FROM dbo.booking_services WHERE booking_id IN (%s)
                ORDER BY booking_id,id
                """.formatted(marks), rs -> {
            while (rs.next()) {
                result.computeIfAbsent(rs.getLong("booking_id"), ignored -> new ArrayList<>())
                        .add(new RequestedService(Long.toString(rs.getLong("service_id")),
                                rs.getString("service_name_snapshot"), rs.getLong("reference_price_snapshot")));
            }
            return null;
        }, bookingIds.toArray());
        return result;
    }

    private static RepairStatus repairStatus(String status) {
        return status == null ? null : RepairStatus.valueOf(status);
    }

    private static String stringId(Long id) {
        return id == null ? null : Long.toString(id);
    }
}
