package vn.garage.repair.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.BookingSlotRepository;

@Repository
public class JdbcBookingSlotRepository implements BookingSlotRepository {
    private final JdbcTemplate db;
    public JdbcBookingSlotRepository(JdbcTemplate db) { this.db = db; }

    @Override
    public LockedSlot lockForBooking(long slotId) {
        var settings = db.queryForObject("""
                SELECT minimum_lead_minutes,maximum_advance_days FROM dbo.garage_settings WITH (HOLDLOCK)
                WHERE id=1
                """, (rs, row) -> new int[]{rs.getInt(1), rs.getInt(2)});
        var rows = db.query("""
                SELECT s.id,s.vehicle_type,s.starts_at,s.ends_at,COALESCE(s.capacity_override,s.capacity) capacity,
                       s.closed,s.manual_closed,
                       (SELECT COUNT(*) FROM dbo.bookings b WHERE b.slot_id=s.id
                         AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')) used
                FROM dbo.appointment_slots s WITH (UPDLOCK,HOLDLOCK,INDEX(PK_appointment_slots))
                WHERE s.id=?
                """, (rs, row) -> new LockedSlot(rs.getLong("id"), rs.getString("vehicle_type"),
                    rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                    rs.getInt("capacity"), rs.getInt("used"), rs.getBoolean("closed") || rs.getBoolean("manual_closed"),
                    settings[0], settings[1]), slotId);
        if (rows.isEmpty()) throw new IllegalArgumentException("SLOT_UNAVAILABLE");
        return rows.getFirst();
    }

    @Override
    public boolean plateAlreadyBooked(long slotId, String normalizedPlate) {
        List<Integer> rows = db.query("""
                SELECT TOP (1) 1 FROM dbo.bookings WITH (UPDLOCK,HOLDLOCK)
                WHERE slot_id=? AND plate_normalized_snapshot=?
                  AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
                """, (rs, row) -> rs.getInt(1), slotId, normalizedPlate);
        return !rows.isEmpty();
    }
}
