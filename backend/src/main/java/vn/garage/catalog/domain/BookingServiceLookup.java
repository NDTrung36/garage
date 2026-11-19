package vn.garage.catalog.domain;

import java.util.List;

/** Read port returning current active service snapshots compatible with a vehicle type. */
public interface BookingServiceLookup {
    List<ServiceSelection> findActiveCompatible(List<Long> serviceIds, String vehicleType);

    record ServiceSelection(long id, String name, long referencePrice) { }
}
