package vn.garage.customer.infrastructure;

import java.util.List;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.customer.domain.CustomerVehicleStore;

@Repository
public class JdbcCustomerVehicleStore implements CustomerVehicleStore {
    private final JdbcTemplate db;

    public JdbcCustomerVehicleStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public long findOrCreateCustomer(ContactAndVehicle contact) {
        List<Long> found = db.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT id FROM dbo.customers WITH (UPDLOCK,HOLDLOCK,INDEX(UX_customers_phone_normalized))
                    WHERE phone_normalized=?
                    """);
            statement.setObject(1, contact.phoneE164(), Types.VARCHAR);
            return statement;
        }, (rs, row) -> rs.getLong(1));
        if (!found.isEmpty()) return found.getFirst();
        return db.queryForObject("""
                INSERT dbo.customers(full_name,phone_normalized)
                OUTPUT INSERTED.id VALUES(?,?)
                """, Long.class, contact.fullName(), contact.phoneE164());
    }

    @Override
    public long findOrCreateVehicle(long customerId, ContactAndVehicle contact) {
        List<Vehicle> found = db.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT id,vehicle_type FROM dbo.vehicles WITH (UPDLOCK,HOLDLOCK,INDEX(UX_vehicles_customer_plate))
                    WHERE customer_id=? AND plate_normalized=?
                    """);
            statement.setLong(1, customerId);
            statement.setNString(2, contact.plateNormalized());
            return statement;
        }, (rs, row) -> new Vehicle(rs.getLong("id"), rs.getString("vehicle_type")));
        if (!found.isEmpty()) {
            Vehicle existing = found.getFirst();
            if (!existing.vehicleType().equals(contact.vehicleType())) {
                throw new IllegalArgumentException("VEHICLE_TYPE_CONFLICT");
            }
            return existing.id();
        }
        return db.queryForObject("""
                INSERT dbo.vehicles(customer_id,vehicle_type,plate_display,plate_normalized,brand,model)
                OUTPUT INSERTED.id VALUES(?,?,?,?,?,?)
                """, Long.class, customerId, contact.vehicleType(), contact.plateDisplay(),
                contact.plateNormalized(), contact.brand(), contact.model());
    }

    private record Vehicle(long id, String vehicleType) { }
}
