package vn.garage.repair.domain;

import java.util.List;
import vn.garage.customer.domain.ContactAndVehicle;

public record GuestBookingRequest(String fullName, String phoneE164, String vehicleType,
                                  String plateDisplay, String plateNormalized, String brand, String model,
                                  List<Long> serviceIds, long slotId, String note) {
    public GuestBookingRequest {
        ContactAndVehicle contact = ContactAndVehicle.from(fullName, phoneE164, vehicleType,
                plateDisplay, brand, model);
        fullName = contact.fullName();
        phoneE164 = contact.phoneE164();
        vehicleType = contact.vehicleType();
        plateDisplay = contact.plateDisplay();
        plateNormalized = contact.plateNormalized();
        brand = contact.brand();
        model = contact.model();
        if (serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > 50
                || serviceIds.stream().anyMatch(id -> id == null || id <= 0)
                || serviceIds.stream().distinct().count() != serviceIds.size()) {
            throw new IllegalArgumentException("INVALID_SERVICE_IDS");
        }
        if (slotId <= 0) throw new IllegalArgumentException("INVALID_SLOT_ID");
        if (note != null && note.length() > 1000) throw new IllegalArgumentException("INVALID_NOTE");
        serviceIds = serviceIds.stream().sorted().toList();
    }

    public static GuestBookingRequest create(String fullName, String phone, String vehicleType,
            String plate, String brand, String model, List<Long> serviceIds, long slotId, String note) {
        String normalizedNote = note == null ? null : note.strip();
        if (normalizedNote != null && normalizedNote.isEmpty()) normalizedNote = null;
        return new GuestBookingRequest(fullName, phone, vehicleType, plate, null,
                brand, model, serviceIds, slotId, normalizedNote);
    }
}
