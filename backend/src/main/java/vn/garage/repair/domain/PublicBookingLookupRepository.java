package vn.garage.repair.domain;

import java.time.OffsetDateTime;
import java.util.List;

public interface PublicBookingLookupRepository {
    long findCustomerIdByPhone(String normalizedPhone);
    LookupData byCustomer(long customerId, int page, int size);
    LookupData byCode(String normalizedCode);

    record LookupData(List<BookingData> bookings, long total) { }
    record BookingData(String bookingCode, OffsetDateTime createdAt, OffsetDateTime startsAt,
                       OffsetDateTime endsAt, String vehicleType, String plate,
                       String status, List<ServiceData> services) { }
    record ServiceData(String name, long referencePrice) { }
}
