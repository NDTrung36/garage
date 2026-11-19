package vn.garage.repair.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuestBookingRequestTest {
    @Test void normalizesAndSortsTheCanonicalRequest() {
        var request = GuestBookingRequest.create(" Nguyễn Văn An ", "0912345678", "MOTORBIKE",
                "59a1-123.45", " Honda ", " Wave ", List.of(9L, 2L), 7, "  Kiểm tra phanh  ");
        assertEquals("+84912345678", request.phoneE164());
        assertEquals("59A1-123.45", request.plateDisplay());
        assertEquals("59A112345", request.plateNormalized());
        assertEquals(List.of(2L, 9L), request.serviceIds());
        assertEquals("Kiểm tra phanh", request.note());
    }

    @Test void rejectsDuplicateOrInvalidServiceIdentifiers() {
        assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                () -> GuestBookingRequest.create("Nguyễn An", "0912345678", "CAR", "30A-12345",
                        null, null, List.of(1L, 1L), 1, null)).getMessage());
    }
}
