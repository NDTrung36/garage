package vn.garage.repair.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairStatus;

@Repository
public class JdbcRepairOrderStore implements RepairOrderStore {
    private final JdbcTemplate db;

    public JdbcRepairOrderStore(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<RepairOrder> findByBookingForUpdate(long bookingId) {
        List<RepairOrder> rows = db.query("""
                SELECT id,booking_id,status,version,received_by,received_at,odometer_km,condition_notes,internal_notes
                FROM dbo.repair_orders WITH (UPDLOCK,HOLDLOCK,INDEX(UX_repair_orders_booking))
                WHERE booking_id=?
                """, (rs, row) -> new RepairOrder(rs.getLong("id"), rs.getLong("booking_id"),
                RepairStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getLong("received_by"),
                rs.getObject("received_at", java.time.OffsetDateTime.class),
                (Integer) rs.getObject("odometer_km"), rs.getString("condition_notes"),
                rs.getString("internal_notes")), bookingId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RepairOrderContext> findByIdForUpdate(long repairOrderId) {
        return db.query("""
                SELECT id,status,version
                FROM dbo.repair_orders WITH (UPDLOCK,HOLDLOCK,INDEX(PK_repair_orders))
                WHERE id=?
                """, (rs, row) -> new RepairOrderContext(rs.getLong("id"),
                RepairStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                null), repairOrderId).stream().findFirst();
    }

    @Override
    public Optional<RepairOrderContext> findById(long repairOrderId) {
        // Keep the immutable vehicle hint separate from the order read. Completion writes booking then order.
        return findBookingId(repairOrderId).flatMap(bookingId -> {
            String vehicleType = db.queryForObject(
                    "SELECT vehicle_type_snapshot FROM dbo.bookings WHERE id=?", String.class, bookingId);
            return db.query("""
                    SELECT id,status,version FROM dbo.repair_orders WHERE id=?
                    """, (rs, row) -> new RepairOrderContext(rs.getLong("id"),
                    RepairStatus.valueOf(rs.getString("status")), rs.getLong("version"), vehicleType),
                    repairOrderId).stream().findFirst();
        });
    }

    @Override
    public Optional<Long> findBookingId(long repairOrderId) {
        return db.query("SELECT booking_id FROM dbo.repair_orders WHERE id=?",
                (rs, row) -> rs.getLong(1), repairOrderId).stream().findFirst();
    }

    @Override
    public Optional<RepairOrderData> findDetails(long repairOrderId) {
        return db.query(DETAIL_SQL + " WHERE ro.id=?", JdbcRepairOrderStore::mapDetails, repairOrderId)
                .stream().findFirst();
    }

    @Override
    public List<RepairOrderData> list(RepairStatus status, int page, int size) {
        String where = status == null ? "" : "WHERE ro.status=?";
        String sql = DETAIL_SQL + " " + where
                + " ORDER BY ro.received_at DESC,ro.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        List<Object> args = new ArrayList<>();
        if (status != null) args.add(status.name());
        args.add((long) page * size);
        args.add(size);
        return db.query(sql, JdbcRepairOrderStore::mapDetails, args.toArray());
    }

    @Override
    public long count(RepairStatus status) {
        Long count = status == null
                ? db.queryForObject("SELECT COUNT_BIG(*) FROM dbo.repair_orders", Long.class)
                : db.queryForObject("SELECT COUNT_BIG(*) FROM dbo.repair_orders WHERE status=?", Long.class, status.name());
        return count == null ? 0 : count;
    }

    @Override
    public boolean updateHeader(long repairOrderId, long expectedVersion,
                                boolean odometerPresent, Integer odometerKm,
                                boolean conditionNotesPresent, String conditionNotes,
                                boolean internalNotesPresent, String internalNotes) {
        List<String> changes = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (odometerPresent) addChange(changes, values, "odometer_km", odometerKm);
        if (conditionNotesPresent) addChange(changes, values, "condition_notes", conditionNotes);
        if (internalNotesPresent) addChange(changes, values, "internal_notes", internalNotes);
        if (changes.isEmpty()) return false;
        values.add(repairOrderId);
        values.add(expectedVersion);
        String sql = "UPDATE dbo.repair_orders SET " + String.join(",", changes)
                + ",version=version+1,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')"
                + " WHERE id=? AND version=? AND status='IN_PROGRESS'";
        return db.update(sql, values.toArray()) == 1;
    }

    private static void addChange(List<String> changes, List<Object> values, String column, Object value) {
        if (value == null) changes.add(column + "=NULL");
        else { changes.add(column + "=?"); values.add(value); }
    }

    private static final String DETAIL_SQL = """
            SELECT ro.id,ro.booking_id,b.code booking_code,b.source booking_source,b.status booking_status,
                   ro.status,ro.version,ro.odometer_km,ro.condition_notes,ro.internal_notes,ro.received_by,
                   ro.received_at,ro.completed_by,ro.completed_at,b.vehicle_type_snapshot,b.contact_name_snapshot,
                   b.contact_phone_snapshot,b.plate_snapshot,b.brand_snapshot,b.model_snapshot
            FROM dbo.repair_orders ro JOIN dbo.bookings b ON b.id=ro.booking_id
            """;

    private static RepairOrderData mapDetails(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RepairOrderData(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("booking_code"),
                rs.getString("booking_source"), BookingStore.BookingStatus.valueOf(rs.getString("booking_status")),
                RepairStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                (Integer) rs.getObject("odometer_km"), rs.getString("condition_notes"), rs.getString("internal_notes"),
                rs.getLong("received_by"), rs.getObject("received_at", java.time.OffsetDateTime.class),
                rs.getObject("completed_by", Long.class), rs.getObject("completed_at", java.time.OffsetDateTime.class),
                rs.getString("vehicle_type_snapshot"), rs.getString("contact_name_snapshot"),
                rs.getString("contact_phone_snapshot"), rs.getString("plate_snapshot"),
                rs.getString("brand_snapshot"), rs.getString("model_snapshot"));
    }

    @Override
    public boolean markCompleted(long repairOrderId, long expectedVersion, long actorId,
                                 java.time.OffsetDateTime completedAt) {
        return db.update("""
                UPDATE dbo.repair_orders SET status='COMPLETED',completed_by=?,completed_at=?,
                    updated_at=?,version=version+1
                WHERE id=? AND status='IN_PROGRESS' AND version=?
                """, actorId, completedAt, completedAt, repairOrderId, expectedVersion) == 1;
    }

    @Override
    public RepairOrder create(NewRepairOrder order) {
        return db.queryForObject("""
                INSERT dbo.repair_orders(booking_id,received_by,received_at,odometer_km,condition_notes,internal_notes)
                OUTPUT INSERTED.id,INSERTED.booking_id,INSERTED.status,INSERTED.version,INSERTED.received_by,
                       INSERTED.received_at,INSERTED.odometer_km,INSERTED.condition_notes,INSERTED.internal_notes
                VALUES(?,?,?,?,?,?)
                """, (rs, row) -> new RepairOrder(rs.getLong("id"), rs.getLong("booking_id"),
                RepairStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getLong("received_by"),
                rs.getObject("received_at", java.time.OffsetDateTime.class), (Integer) rs.getObject("odometer_km"),
                rs.getString("condition_notes"), rs.getString("internal_notes")), order.bookingId(),
                order.receivedBy(), order.receivedAt(), order.odometerKm(), order.conditionNotes(), order.internalNotes());
    }
}
