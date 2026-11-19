package vn.garage.repair.application;

import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.customer.domain.CustomerContactRules;
import vn.garage.repair.domain.PublicBookingLookupRepository;

@Service
public class PublicBookingLookup {
    private final PublicBookingLookupRepository bookings;
    public PublicBookingLookup(PublicBookingLookupRepository bookings) { this.bookings = bookings; }

    @Transactional(readOnly = true)
    public LookupPage lookup(String phone, String bookingCode, int page, int size) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasCode = bookingCode != null && !bookingCode.isBlank();
        if (hasPhone == hasCode) throw new IllegalArgumentException("LOOKUP_CRITERION_REQUIRED");
        if (size < 1 || size > 100 || page < 0) throw new IllegalArgumentException("INVALID_PAGE");
        PublicBookingLookupRepository.LookupData data;
        if (hasPhone) {
            String normalized = CustomerContactRules.normalizeVietnameseMobile(phone);
            long customerId = bookings.findCustomerIdByPhone(normalized);
            data = customerId == 0 ? new PublicBookingLookupRepository.LookupData(List.of(), 0)
                    : bookings.byCustomer(customerId, page, size);
        } else {
            String normalized = bookingCode.trim().toUpperCase(java.util.Locale.ROOT);
            if (!normalized.matches("BK-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}")) {
                throw new IllegalArgumentException("INVALID_BOOKING_CODE");
            }
            data = bookings.byCode(normalized);
            page = 0; size = 1;
        }
        List<LookupItem> items = data.bookings().stream().map(value -> {
            long total = value.services().stream().mapToLong(PublicBookingLookupRepository.ServiceData::referencePrice)
                    .reduce(0L, Math::addExact);
            return new LookupItem(value.bookingCode(), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.createdAt()),
                    value.startsAt() == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.startsAt()),
                    value.endsAt() == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.endsAt()),
                    value.vehicleType(), maskPlate(value.plate()), value.status(),
                    value.services().stream().map(item -> new LookupService(item.name(), item.referencePrice())).toList(), total);
        }).toList();
        return new LookupPage(items, page, size, data.total(), Math.ceilDiv(data.total(), size));
    }

    static String maskPlate(String plate) {
        int visible = Math.min(3, Math.max(0, plate.codePoints().filter(Character::isLetterOrDigit).toArray().length - 1));
        int remaining = visible;
        StringBuilder result = new StringBuilder(plate.length());
        for (int i = plate.length() - 1; i >= 0; i--) {
            char ch = plate.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (remaining > 0) { remaining--; result.append(ch); }
                else result.append('*');
            } else result.append(ch);
        }
        return result.reverse().toString();
    }

    public record LookupService(String name, long referencePrice) { }
    public record LookupItem(String bookingCode, String createdAt, String startsAt, String endsAt,
            String vehicleType, String maskedPlate, String status, List<LookupService> services, long referenceTotal) { }
    public record LookupPage(List<LookupItem> items, int page, int size, long totalElements, long totalPages) { }
}
