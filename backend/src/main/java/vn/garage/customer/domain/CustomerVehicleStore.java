package vn.garage.customer.domain;

/** Persistence port for reusing customer and vehicle records by their unique natural keys. */
public interface CustomerVehicleStore {
    long findOrCreateCustomer(ContactAndVehicle contact);

    long findOrCreateVehicle(long customerId, ContactAndVehicle contact);
}
