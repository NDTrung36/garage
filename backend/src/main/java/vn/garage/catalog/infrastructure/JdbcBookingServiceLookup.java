package vn.garage.catalog.infrastructure;

import java.util.List;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.catalog.domain.BookingServiceLookup;

@Repository
public class JdbcBookingServiceLookup implements BookingServiceLookup {
    private final JdbcTemplate db;

    public JdbcBookingServiceLookup(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public List<ServiceSelection> findActiveCompatible(List<Long> serviceIds, String vehicleType) {
        if (serviceIds == null || serviceIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(serviceIds.size(), "?"));
        String sql = """
                SELECT id,name,reference_price FROM dbo.services WITH (HOLDLOCK)
                WHERE active=1 AND (vehicle_type=? OR vehicle_type='BOTH') AND id IN (%s)
                ORDER BY id
                """.formatted(marks);
        return db.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setObject(1, vehicleType, Types.VARCHAR);
            for (int i = 0; i < serviceIds.size(); i++) statement.setLong(i + 2, serviceIds.get(i));
            return statement;
        }, (rs, row) -> new ServiceSelection(
                rs.getLong("id"), rs.getString("name"), rs.getLong("reference_price")));
    }
}
