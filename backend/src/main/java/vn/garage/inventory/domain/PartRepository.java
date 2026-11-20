package vn.garage.inventory.domain;

import java.util.Optional;

public interface PartRepository {
    final class DuplicateCode extends RuntimeException { }
    final class StaleVersion extends RuntimeException { }
    final class UnitLocked extends RuntimeException { }

    PartPage list(int page, int size, String query, Boolean active, String vehicleType);
    Optional<PartView> findById(long id);
    PartView create(Part part);
    Optional<PartView> update(long id, String name, String unit, String vehicleType,
                              Long referencePrice, Boolean active, long expectedVersion);
    boolean hasMovements(long partId);
}
