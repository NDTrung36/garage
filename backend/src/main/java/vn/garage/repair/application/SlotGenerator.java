package vn.garage.repair.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.repair.domain.SchedulePolicy;
import vn.garage.repair.domain.SlotPlan;
import vn.garage.repair.domain.SlotPlan.Hours;

@Service
public class SlotGenerator {
    public static final String TIME_ZONE = "Asia/Ho_Chi_Minh";
    private static final ZoneId ZONE = ZoneId.of(TIME_ZONE);
    private static final String UTC_NOW = "TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')";
    private static final String USED = """
            (SELECT COUNT(*) FROM dbo.bookings b WHERE b.slot_id=s.id
             AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED'))
            """;
    private static final RowMapper<SlotView> SLOT_ROW = (rs, index) -> new SlotView(
            rs.getLong("id"), rs.getObject("starts_at", OffsetDateTime.class),
            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("vehicle_type"),
            rs.getInt("capacity"), rs.getBoolean("closed"), rs.getBoolean("manual_closed"),
            rs.getObject("capacity_override", Integer.class), rs.getLong("version"), rs.getInt("used"));

    private final JdbcTemplate db;
    private final Clock clock;

    public SlotGenerator(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Settings(int slotMinutes, int minimumLeadMinutes, int maximumAdvanceDays,
                           int carCapacity, int motorbikeCapacity, LocalDate generatedThrough, long version) { }
    public record ScheduleSettings(Settings settings, List<Hours> workingHours) { }
    public record ClosedDate(String id, LocalDate date, String reason) { }
    public record PublicSlot(String id, String startsAt, String endsAt, int remainingCapacity, boolean closed) { }
    public record AdminSlot(String id, String startsAt, String endsAt, String vehicleType, int capacity, int used,
                            boolean closed, boolean manualClosed, Integer capacityOverride, long version) { }
    private record SlotView(long id, OffsetDateTime start, OffsetDateTime end, String type, int capacity,
                            boolean closed, boolean manualClosed, Integer capacityOverride, long version, int used) {
        AdminSlot adminView() {
            return new AdminSlot(Long.toString(id), formatTime(start), formatTime(end), type, capacity,
                    used, closed, manualClosed, capacityOverride, version);
        }
    }

    public static class ScheduleException extends RuntimeException {
        private final int status;
        public ScheduleException(int status, String code) { super(code); this.status = status; }
        public int status() { return status; }
    }

    @Transactional
    public LocalDate regenerate() {
        var settings = settings(true);
        return reconcile(settings, now());
    }

    @Transactional(readOnly = true)
    public ScheduleSettings scheduleSettings() {
        return new ScheduleSettings(settings(false), workingHours());
    }

    @Transactional
    public ScheduleSettings updateSettings(Settings requested, List<Hours> hours, long expectedVersion) {
        Map<Integer, List<LocalTime[]>> grouped = new HashMap<>();
        for (Hours period : hours) {
            if (period == null || period.opensAt() == null || period.closesAt() == null) {
                throw new IllegalArgumentException("INVALID_WORKING_HOURS");
            }
            grouped.computeIfAbsent(period.dayOfWeek(), ignored -> new java.util.ArrayList<>())
                    .add(new LocalTime[]{period.opensAt(), period.closesAt()});
        }
        SchedulePolicy.validate(requested.slotMinutes(), requested.minimumLeadMinutes(),
                requested.maximumAdvanceDays(), requested.carCapacity(), requested.motorbikeCapacity(), grouped);
        requireVersion(expectedVersion, settings(true).version());
        db.update("""
                UPDATE dbo.garage_settings SET slot_minutes=?, minimum_lead_minutes=?,
                    maximum_advance_days=?, car_capacity=?, motorbike_capacity=?, version=version+1, updated_at=
                """ + UTC_NOW + " WHERE id=1", requested.slotMinutes(), requested.minimumLeadMinutes(),
                requested.maximumAdvanceDays(), requested.carCapacity(), requested.motorbikeCapacity());
        db.update("DELETE FROM dbo.working_hours");
        db.batchUpdate("INSERT INTO dbo.working_hours(day_of_week,opens_at,closes_at) VALUES(?,?,?)",
                hours.stream().map(h -> new Object[]{h.dayOfWeek(), h.opensAt(), h.closesAt()}).toList());
        reconcile(settings(false), now());
        return scheduleSettings();
    }

    @Transactional(readOnly = true)
    public List<ClosedDate> closedDates(LocalDate from, LocalDate through) {
        if (through.isBefore(from)) throw new IllegalArgumentException("INVALID_DATE_RANGE");
        return db.query("""
                SELECT id, closed_date, reason FROM dbo.calendar_exceptions
                WHERE closed_date BETWEEN ? AND ? ORDER BY closed_date
                """, (rs, index) -> new ClosedDate(Long.toString(rs.getLong(1)),
                rs.getObject(2, LocalDate.class), rs.getString(3)), from, through);
    }

    @Transactional
    public ClosedDate addClosedDate(LocalDate date, String reason, long expectedVersion) {
        Settings settings = settings(true);
        requireVersion(expectedVersion, settings.version());
        var now = now();
        if (date.isBefore(now.toLocalDate()) || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("INVALID_CLOSED_DATE");
        }
        try {
            db.update("INSERT INTO dbo.calendar_exceptions(closed_date,reason) VALUES(?,?)", date, reason.trim());
        } catch (DuplicateKeyException ex) {
            throw new ScheduleException(409, "CLOSED_DATE_EXISTS");
        }
        advanceSettingsVersion();
        reconcile(settings, now);
        return closedDates(date, date).getFirst();
    }

    @Transactional
    public void removeClosedDate(long id, long expectedVersion) {
        Settings settings = settings(true);
        requireVersion(expectedVersion, settings.version());
        var now = now();
        var dates = db.query("SELECT closed_date FROM dbo.calendar_exceptions WHERE id=?",
                (rs, index) -> rs.getObject(1, LocalDate.class), id);
        if (dates.isEmpty()) throw new ScheduleException(404, "CLOSED_DATE_NOT_FOUND");
        if (dates.getFirst().isBefore(now.toLocalDate())) throw new ScheduleException(409, "PAST_CLOSED_DATE");
        db.update("DELETE FROM dbo.calendar_exceptions WHERE id=?", id);
        advanceSettingsVersion();
        reconcile(settings, now);
    }

    @Transactional(readOnly = true)
    public List<AdminSlot> adminSlots(LocalDate date, String type) {
        validateVehicleType(type);
        return db.query("SELECT s.*, " + USED + """
                 AS used FROM dbo.appointment_slots s
                 WHERE s.starts_at>=? AND s.starts_at<? AND s.vehicle_type=? ORDER BY s.starts_at
                """, SLOT_ROW, firstInstant(date), firstInstant(date.plusDays(1)), type)
                .stream().map(SlotView::adminView).toList();
    }

    @Transactional
    public AdminSlot updateSlot(long id, boolean manualClosed, Integer override, long expectedVersion) {
        Settings settings = settings(true);
        var now = now();
        if (override != null && (override < 0 || override > 100)) {
            throw new IllegalArgumentException("INVALID_CAPACITY_OVERRIDE");
        }
        lockSlots(firstInstant(now.toLocalDate()));
        var matches = slotsById(id);
        if (matches.isEmpty()) throw new ScheduleException(404, "SLOT_NOT_FOUND");
        SlotView current = matches.getFirst();
        requireVersion(expectedVersion, current.version());
        if (!current.start().isAfter(now)) throw new ScheduleException(409, "SLOT_ALREADY_STARTED");
        int defaultCapacity = current.type().equals("CAR") ? settings.carCapacity() : settings.motorbikeCapacity();
        int capacity = override != null ? override
                : current.capacityOverride() == null ? current.capacity() : defaultCapacity;
        if (capacity < current.used()) throw new ScheduleException(409, "CAPACITY_BELOW_USAGE");
        // Reconciliation decides whether the interval can open under the current working hours.
        db.update("""
                UPDATE dbo.appointment_slots SET manual_closed=?, capacity_override=?, capacity=?,
                    version=version+1, updated_at=
                """ + UTC_NOW + " WHERE id=?", manualClosed, override, capacity, id);
        reconcile(settings, now);
        var updated = slotsById(id);
        // Clearing the last override may delete a slot outside the current template.
        return updated.isEmpty() ? null : updated.getFirst().adminView();
    }

    @Transactional(readOnly = true)
    public List<PublicSlot> availability(LocalDate date, String type) {
        validateVehicleType(type);
        var now = now();
        var settings = settings(false);
        if (date.isBefore(now.toLocalDate()) || date.isAfter(now.toLocalDate().plusDays(settings.maximumAdvanceDays()))) {
            throw new IllegalArgumentException("OUTSIDE_BOOKING_WINDOW");
        }
        if (settings.generatedThrough() == null || date.isAfter(settings.generatedThrough())) {
            throw new ScheduleException(503, "SLOT_SCHEDULE_UNAVAILABLE");
        }
        return db.query("""
                SELECT s.id, s.starts_at, s.ends_at,
                       COALESCE(s.capacity_override,s.capacity)-usage_count.used AS remaining
                FROM dbo.appointment_slots s
                CROSS APPLY (SELECT
                """ + USED + """
                    AS used) usage_count
                WHERE s.starts_at>=? AND s.starts_at<? AND s.starts_at>=? AND s.vehicle_type=?
                  AND s.closed=0 AND s.manual_closed=0 AND COALESCE(s.capacity_override,s.capacity)>usage_count.used
                ORDER BY s.starts_at
                """, (rs, index) -> new PublicSlot(Long.toString(rs.getLong("id")),
                    formatTime(rs.getObject("starts_at", OffsetDateTime.class)),
                    formatTime(rs.getObject("ends_at", OffsetDateTime.class)),
                    rs.getInt("remaining"), false),
                firstInstant(date), firstInstant(date.plusDays(1)), now.plusMinutes(settings.minimumLeadMinutes()), type);
    }

    private LocalDate reconcile(Settings settings, OffsetDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate through = today.plusDays(settings.maximumAdvanceDays());
        OffsetDateTime lowerBound = firstInstant(today);

        // Settings serializes schedule writers. Lock existing slots by PK before looking up booking references.
        lockSlots(lowerBound);
        var existing = db.query("""
                SELECT s.*, CASE WHEN EXISTS (SELECT 1 FROM dbo.bookings b WHERE b.slot_id=s.id)
                    THEN 1 ELSE 0 END AS has_booking
                FROM dbo.appointment_slots s WHERE s.starts_at>=? ORDER BY s.id
                """, (rs, index) -> new SlotPlan.Existing(rs.getLong("id"),
                    new SlotPlan.SlotTime(rs.getObject("starts_at", OffsetDateTime.class),
                            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("vehicle_type")),
                    rs.getInt("capacity"), rs.getBoolean("closed"), rs.getBoolean("manual_closed"),
                    rs.getObject("capacity_override", Integer.class), rs.getBoolean("has_booking")), lowerBound);
        var holidayDates = closedDates(today, through).stream().map(ClosedDate::date).toList();
        var changes = SlotPlan.reconcile(now, through, ZONE, settings.slotMinutes(), settings.carCapacity(),
                settings.motorbikeCapacity(), workingHours(), new HashSet<>(holidayDates), existing);

        db.batchUpdate("DELETE FROM dbo.appointment_slots WHERE id=?",
                changes.deletes().stream().map(id -> new Object[]{id}).toList());
        db.batchUpdate("UPDATE dbo.appointment_slots SET capacity=?, closed=?, version=version+1, updated_at="
                        + UTC_NOW + " WHERE id=?",
                changes.updates().stream().map(row -> new Object[]{row.capacity(), row.closed(), row.id()}).toList());
        db.batchUpdate("INSERT INTO dbo.appointment_slots(starts_at,ends_at,vehicle_type,capacity) VALUES(?,?,?,?)",
                changes.inserts().stream().map(row -> new Object[]{row.time().startsAt(), row.time().endsAt(),
                        row.time().vehicleType(), row.capacity()}).toList());
        if (!through.equals(settings.generatedThrough())) {
            db.update("UPDATE dbo.garage_settings SET generated_through=?, updated_at=" + UTC_NOW + " WHERE id=1", through);
        }
        return through;
    }

    private Settings settings(boolean lock) {
        return db.queryForObject("""
                SELECT slot_minutes, minimum_lead_minutes, maximum_advance_days, car_capacity, motorbike_capacity,
                       generated_through, version FROM dbo.garage_settings
                """ + (lock ? " WITH (UPDLOCK, HOLDLOCK)" : "") + " WHERE id=1",
                (rs, index) -> new Settings(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getObject(6, LocalDate.class), rs.getLong(7)));
    }

    private List<Hours> workingHours() {
        return db.query("SELECT day_of_week,opens_at,closes_at FROM dbo.working_hours ORDER BY day_of_week,opens_at",
                (rs, index) -> new Hours(rs.getInt(1), rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)));
    }

    private List<SlotView> slotsById(long id) {
        return db.query("SELECT s.*, " + USED + " AS used FROM dbo.appointment_slots s WHERE id=?", SLOT_ROW, id);
    }

    private void lockSlots(OffsetDateTime lowerBound) {
        db.query("""
                SELECT id FROM dbo.appointment_slots WITH (UPDLOCK, INDEX(PK_appointment_slots))
                WHERE starts_at>=? ORDER BY id OPTION (MAXDOP 1)
                """, (rs, index) -> rs.getLong(1), lowerBound);
    }

    private void advanceSettingsVersion() {
        db.update("UPDATE dbo.garage_settings SET version=version+1, updated_at=" + UTC_NOW + " WHERE id=1");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(ZONE));
    }

    private static OffsetDateTime firstInstant(LocalDate date) {
        return date.atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static String formatTime(OffsetDateTime dateTime) {
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) throw new ScheduleException(409, "STALE_VERSION");
    }

    private static void validateVehicleType(String type) {
        if (!"CAR".equals(type) && !"MOTORBIKE".equals(type)) throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
    }
}
