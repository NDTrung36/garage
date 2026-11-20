package vn.garage.inventory.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.inventory.domain.Part;
import vn.garage.inventory.domain.PartPage;
import vn.garage.inventory.domain.PartRepository;
import vn.garage.inventory.domain.PartView;

@Repository
public class JdbcPartRepository implements PartRepository {
    private static final String COLUMNS = "id, code, name, unit, vehicle_type, reference_price, stock_quantity, active, version, created_at, updated_at";
    private static final RowMapper<PartView> ROW = (rs, row) -> new PartView(
            Long.toString(rs.getLong("id")), rs.getString("code"), rs.getString("name"),
            rs.getString("unit"), rs.getString("vehicle_type"), rs.getLong("reference_price"),
            rs.getBigDecimal("stock_quantity").setScale(3).toPlainString(), rs.getBoolean("active"),
            rs.getLong("version"), rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate db;

    public JdbcPartRepository(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public PartPage list(int page, int size, String query, Boolean active, String vehicleType) {
        String text = query == null || query.isBlank() ? null : query.trim();
        String pattern = text == null ? null : "%" + escapeLike(text) + "%";
        String filter = " FROM dbo.parts WHERE (? IS NULL OR active=?)"
                + " AND (? IS NULL OR vehicle_type=? OR vehicle_type='BOTH')"
                + " AND (? IS NULL OR code LIKE ? OR name LIKE ?)";
        long total = db.queryForObject("SELECT COUNT_BIG(*)" + filter, Long.class,
                active, active, vehicleType, vehicleType, pattern, pattern, pattern);
        List<PartView> items = db.query("SELECT " + COLUMNS + filter
                        + " ORDER BY created_at DESC,id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                ROW, active, active, vehicleType, vehicleType, pattern, pattern, pattern, (long) page * size, size);
        return new PartPage(items, page, size, total, Math.ceilDiv(total, size));
    }

    @Override
    public Optional<PartView> findById(long id) {
        List<PartView> rows = db.query("SELECT " + COLUMNS + " FROM dbo.parts WHERE id=?", ROW, id);
        return rows.stream().findFirst();
    }

    @Override
    public PartView create(Part part) {
        try {
            db.update("INSERT dbo.parts(code,name,unit,vehicle_type,reference_price) VALUES(?,?,?,?,?)",
                    part.code(), part.name(), part.unit(), part.vehicleType(), part.referencePrice());
        } catch (DuplicateKeyException duplicate) {
            if (isPartCodeConflict(duplicate)) throw new PartRepository.DuplicateCode();
            throw duplicate;
        }
        return db.queryForObject("SELECT " + COLUMNS + " FROM dbo.parts WHERE code=?", ROW, part.code());
    }

    @Override
    @Transactional
    public Optional<PartView> update(long id, String name, String unit, String vehicleType,
                                     Long referencePrice, Boolean active, long expectedVersion) {
        List<PartView> locked = db.query("SELECT " + COLUMNS + " FROM dbo.parts WITH (UPDLOCK,HOLDLOCK) WHERE id=?", ROW, id);
        if (locked.isEmpty()) return Optional.empty();
        PartView current = locked.getFirst();
        if (current.version() != expectedVersion) throw new PartRepository.StaleVersion();
        String nextUnit = unit == null ? current.unit() : unit.trim();
        if (!nextUnit.equals(current.unit()) && hasMovements(id)) throw new PartRepository.UnitLocked();

        db.update("""
                UPDATE dbo.parts SET name=?,unit=?,vehicle_type=?,reference_price=?,active=?,version=version+1,
                    updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00') WHERE id=?
                """, name == null ? current.name() : name.trim(), nextUnit,
                vehicleType == null ? current.vehicleType() : vehicleType,
                referencePrice == null ? current.referencePrice() : referencePrice,
                active == null ? current.active() : active, id);
        return Optional.of(db.queryForObject("SELECT " + COLUMNS + " FROM dbo.parts WHERE id=?", ROW, id));
    }

    @Override
    public boolean hasMovements(long partId) {
        return !db.query("SELECT TOP (1) id FROM dbo.stock_movements WHERE part_id=?", (rs, row) -> rs.getLong(1), partId).isEmpty();
    }

    private static String escapeLike(String value) {
        return value.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]");
    }

    private static boolean isPartCodeConflict(DuplicateKeyException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains("UX_parts_code")) return true;
            cause = cause.getCause();
        }
        return false;
    }

}
