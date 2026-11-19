package vn.garage.customer.domain;

/** Canonical contact and vehicle facts supplied for one intake or booking snapshot. */
public record ContactAndVehicle(String fullName, String phoneE164, String vehicleType,
                                String plateDisplay, String brand, String model) {
    public ContactAndVehicle {
        fullName = CustomerContactRules.normalizeName(fullName);
        phoneE164 = CustomerContactRules.normalizeVietnameseMobile(phoneE164);
        if (!"CAR".equals(vehicleType) && !"MOTORBIKE".equals(vehicleType)) {
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        }
        plateDisplay = CustomerContactRules.normalizePlateDisplay(plateDisplay);
        brand = CustomerContactRules.normalizeOptionalVehicleText(brand);
        model = CustomerContactRules.normalizeOptionalVehicleText(model);
    }

    public static ContactAndVehicle from(String fullName, String phone, String vehicleType,
                                         String plate, String brand, String model) {
        return new ContactAndVehicle(fullName, phone, vehicleType, plate, brand, model);
    }

    public String plateNormalized() {
        return CustomerContactRules.normalizePlate(plateDisplay);
    }
}
