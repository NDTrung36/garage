package vn.garage.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.inventory.domain.Part;
import vn.garage.inventory.domain.PartPage;
import vn.garage.inventory.domain.PartRepository;
import vn.garage.inventory.domain.PartView;
import vn.garage.support.audit.AuditWriter;

@Service
public class PartCatalog {
    private final PartRepository parts;
    private final AuditWriter audit;

    public PartCatalog(PartRepository parts, AuditWriter audit) {
        this.parts = parts;
        this.audit = audit;
    }

    public static final class NotFound extends RuntimeException { }
    public static final class DuplicateCode extends RuntimeException { }
    public static final class UnitLocked extends RuntimeException { }

    @Transactional(readOnly = true)
    public PartPage list(int page, int size, String query, Boolean active, String vehicleType) {
        validatePage(page, size);
        if (vehicleType != null && !"CAR".equals(vehicleType) && !"MOTORBIKE".equals(vehicleType)) {
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        }
        return parts.list(page, size, query, active, vehicleType);
    }

    @Transactional
    public PartView create(String code, String name, String unit, String vehicleType, long referencePrice,
                           String actorRole, long actorId) {
        requireAdmin(actorRole);
        try {
            PartView created = parts.create(new Part(code, name, unit, vehicleType, referencePrice));
            audit.write(actorId, "PART_CREATED", "part", Long.parseLong(created.id()), null, null,
                    PartAuditSnapshot.from(created));
            return created;
        } catch (PartRepository.DuplicateCode conflict) {
            throw new DuplicateCode();
        }
    }

    @Transactional
    public PartView update(long id, String name, String unit, String vehicleType, Long referencePrice,
                           Boolean active, long expectedVersion, String actorRole, long actorId) {
        requireAdmin(actorRole);
        if (id < 1 || expectedVersion < 0) throw new IllegalArgumentException("INVALID_PART_ID_OR_VERSION");
        if (name == null && unit == null && vehicleType == null && referencePrice == null && active == null) {
            throw new IllegalArgumentException("EMPTY_PART_UPDATE");
        }
        if (name != null && (name.isBlank() || name.trim().length() > 150)) throw new IllegalArgumentException("INVALID_PART_NAME");
        if (unit != null && (unit.isBlank() || unit.trim().length() > 30)) throw new IllegalArgumentException("INVALID_PART_UNIT");
        if (vehicleType != null && !"CAR".equals(vehicleType) && !"MOTORBIKE".equals(vehicleType) && !"BOTH".equals(vehicleType))
            throw new IllegalArgumentException("INVALID_VEHICLE_TYPE");
        if (referencePrice != null && (referencePrice < 0 || referencePrice > 9_007_199_254_740_991L))
            throw new IllegalArgumentException("INVALID_REFERENCE_PRICE");
        try {
            PartView before = parts.findById(id).orElseThrow(NotFound::new);
            PartView updated = parts.update(id, name, unit, vehicleType, referencePrice, active, expectedVersion)
                    .orElseThrow(NotFound::new);
            audit.write(actorId, "PART_UPDATED", "part", id, null,
                    PartAuditSnapshot.from(before), PartAuditSnapshot.from(updated));
            return updated;
        } catch (PartRepository.StaleVersion stale) {
            throw new IllegalStateException("STALE_VERSION", stale);
        } catch (PartRepository.UnitLocked locked) {
            throw new UnitLocked();
        }
    }

    private static void requireAdmin(String actorRole) {
        if (!"ADMIN".equals(actorRole)) throw new IllegalStateException("FORBIDDEN");
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_PAGE");
    }

    private record PartAuditSnapshot(String code, String name, String unit, String vehicleType,
                                     long referencePrice, boolean active, long version) {
        private static PartAuditSnapshot from(PartView part) {
            return new PartAuditSnapshot(part.code(), part.name(), part.unit(), part.vehicleType(),
                    part.referencePrice(), part.active(), part.version());
        }
    }
}
