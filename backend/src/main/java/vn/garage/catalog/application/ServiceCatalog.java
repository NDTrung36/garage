package vn.garage.catalog.application;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceCatalog {
    private static final String COLUMNS = "id, code, name, description, vehicle_type, reference_price, active, version";
    private static final RowMapper<ServiceView> ROW = (rs, index) -> new ServiceView(
            Long.toString(rs.getLong("id")), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getString("vehicle_type"), rs.getLong("reference_price"),
            rs.getBoolean("active"), rs.getLong("version"));

    private final JdbcTemplate db;

    public ServiceCatalog(JdbcTemplate db) {
        this.db = db;
    }

    public record ServiceView(String id, String code, String name, String description,
                              String vehicleType, long price, boolean active, long version) { }

    public record ServicePage(List<ServiceView> items, int page, int size, long totalElements, long totalPages) { }

    public static final class NotFound extends RuntimeException { }
    public static final class DuplicateCode extends RuntimeException { }

    public List<ServiceView> publicList(String type) {
        if (!"CAR".equals(type) && !"MOTORBIKE".equals(type)) {
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        }
        return db.query("SELECT " + COLUMNS + """
                 FROM dbo.services WHERE active=1 AND (vehicle_type=? OR vehicle_type='BOTH') ORDER BY name,id
                """, ROW, type);
    }

    @Transactional(readOnly = true)
    public ServicePage adminList(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_PAGE");
        long total = db.queryForObject("SELECT COUNT_BIG(*) FROM dbo.services", Long.class);
        var items = db.query("SELECT " + COLUMNS + """
                 FROM dbo.services ORDER BY id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, ROW, (long) page * size, size);
        return new ServicePage(items, page, size, total, Math.ceilDiv(total, size));
    }

    @Transactional
    public ServiceView create(String code, String name, String description, String type, long price) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,40}")) throw new IllegalArgumentException("INVALID_SERVICE_CODE");
        validate(name, description, type, price);
        try {
            db.update("""
                    INSERT INTO dbo.services(code,name,description,vehicle_type,reference_price)
                    VALUES(?,?,?,?,?)
                    """, normalized, name.trim(), description, type, price);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateCode();
        }
        return db.queryForObject("SELECT " + COLUMNS + " FROM dbo.services WHERE code=?", ROW, normalized);
    }

    @Transactional
    public ServiceView update(long id, String name, String description, String type, Long price,
                              Boolean active, long version) {
        validate(name, description, type, price);
        int count = db.update("""
                UPDATE dbo.services
                SET name=?, description=?, vehicle_type=?, reference_price=COALESCE(?,reference_price),
                    active=COALESCE(?,active), version=version+1,
                    updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE id=? AND version=?
                """, name.trim(), description, type, price, active, id, version);
        if (count == 0) {
            if (db.queryForObject("SELECT COUNT(*) FROM dbo.services WHERE id=?", Integer.class, id) == 0) {
                throw new NotFound();
            }
            throw new IllegalStateException("STALE_VERSION");
        }
        return db.queryForObject("SELECT " + COLUMNS + " FROM dbo.services WHERE id=?", ROW, id);
    }

    private static void validate(String name, String description, String type, Long price) {
        if (name == null || name.isBlank() || name.trim().length() > 150
                || (description != null && description.length() > 1000)
                || (price != null && (price < 0 || price > 9_007_199_254_740_991L))) {
            throw new IllegalArgumentException("INVALID_SERVICE");
        }
        if (!"CAR".equals(type) && !"MOTORBIKE".equals(type) && !"BOTH".equals(type)) {
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        }
    }
}
