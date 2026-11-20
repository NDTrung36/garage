package vn.garage.inventory.domain;

import java.util.Locale;

public record Part(String code, String name, String unit, String vehicleType, long referencePrice) {
    public Part {
        code = normalizeCode(code);
        name = requiredText(name, 150, "INVALID_PART_NAME");
        unit = requiredText(unit, 30, "INVALID_PART_UNIT");
        if (!"CAR".equals(vehicleType) && !"MOTORBIKE".equals(vehicleType) && !"BOTH".equals(vehicleType)) {
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        }
        if (referencePrice < 0 || referencePrice > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("INVALID_REFERENCE_PRICE");
        }
    }

    public static String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("INVALID_PART_CODE");
        }
        return normalized;
    }

    private static String requiredText(String value, int maxLength, String errorCode) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(errorCode);
        }
        return normalized;
    }
}
