package vn.garage.repair.infrastructure;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.PublicBookingLookupRepository;

@Repository
public class JdbcPublicBookingLookupRepository implements PublicBookingLookupRepository {
    private final JdbcTemplate db;
    public JdbcPublicBookingLookupRepository(JdbcTemplate db) { this.db = db; }

    @Override
    public long findCustomerIdByPhone(String phone) {
        List<Long> ids = db.query("SELECT id FROM dbo.customers WHERE phone_normalized=?",
                (rs, row) -> rs.getLong(1), phone);
        return ids.isEmpty() ? 0 : ids.getFirst();
    }

    @Override
    public LookupData byCustomer(long customerId, int page, int size) {
        long total = db.queryForObject("SELECT COUNT_BIG(*) FROM dbo.bookings WHERE customer_id=?", Long.class, customerId);
        var rows = db.query("""
                SELECT b.code,b.created_at,b.vehicle_type_snapshot,b.plate_snapshot,b.status,s.starts_at,s.ends_at
                FROM dbo.bookings b LEFT JOIN dbo.appointment_slots s ON s.id=b.slot_id
                WHERE b.customer_id=? ORDER BY b.created_at DESC,b.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, row) -> new Row(rs.getString("code"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getString("vehicle_type_snapshot"), rs.getString("plate_snapshot"), rs.getString("status")),
                customerId, (long) page * size, size);
        return new LookupData(addServices(rows), total);
    }

    @Override
    public LookupData byCode(String code) {
        var rows = db.query("""
                SELECT b.code,b.created_at,b.vehicle_type_snapshot,b.plate_snapshot,b.status,s.starts_at,s.ends_at
                FROM dbo.bookings b LEFT JOIN dbo.appointment_slots s ON s.id=b.slot_id WHERE b.code=?
                """, (rs, row) -> new Row(rs.getString("code"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getString("vehicle_type_snapshot"), rs.getString("plate_snapshot"), rs.getString("status")), code);
        return new LookupData(addServices(rows), rows.size());
    }

    private List<BookingData> addServices(List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(Row::code).toArray();
        Map<String, List<ServiceData>> serviceMap = new LinkedHashMap<>();
        db.query("""
                SELECT b.code,bs.service_name_snapshot,bs.reference_price_snapshot
                FROM dbo.booking_services bs JOIN dbo.bookings b ON b.id=bs.booking_id
                WHERE b.code IN (%s) ORDER BY bs.id
                """.formatted(marks), rs -> {
            String code = rs.getString(1);
            serviceMap.computeIfAbsent(code, ignored -> new ArrayList<>())
                    .add(new ServiceData(rs.getString(2), rs.getLong(3)));
        }, ids);
        return rows.stream().map(row -> new BookingData(row.code(), row.createdAt(), row.startsAt(), row.endsAt(),
                row.vehicleType(), row.plate(), row.status(), serviceMap.getOrDefault(row.code(), List.of()))).toList();
    }

    private record Row(String code, OffsetDateTime createdAt, OffsetDateTime startsAt,
                       OffsetDateTime endsAt, String vehicleType, String plate, String status) { }
}
