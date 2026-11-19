package vn.garage.customer.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.customer.domain.CustomerVehicleStore;

/** Shared customer/vehicle onboarding for guest bookings and repair intake. */
@Service
public class CustomerVehicleOnboarding {
    private final CustomerVehicleStore store;

    public CustomerVehicleOnboarding(CustomerVehicleStore store) {
        this.store = store;
    }

    @Transactional
    public OnboardedVehicle obtain(ContactAndVehicle contact) {
        long customerId = store.findOrCreateCustomer(contact);
        long vehicleId = store.findOrCreateVehicle(customerId, contact);
        return new OnboardedVehicle(customerId, vehicleId, contact);
    }

    public record OnboardedVehicle(long customerId, long vehicleId, ContactAndVehicle snapshot) { }
}
