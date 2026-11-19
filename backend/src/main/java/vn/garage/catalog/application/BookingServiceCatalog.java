package vn.garage.catalog.application;

import java.util.List;

public interface BookingServiceCatalog {
    List<ServiceSnapshot> activeForBooking(List<Long> serviceIds, String vehicleType);

    record ServiceSnapshot(long id, String name, long referencePrice) { }
}
