package vn.garage.inventory.domain;

import java.time.OffsetDateTime;

public record PartView(String id, String code, String name, String unit, String vehicleType,
                       long referencePrice, String stockQuantity, boolean active, long version,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
