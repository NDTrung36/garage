package vn.garage.repair.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.garage.repair.domain.PublicBookingLookupRepository;

class PublicBookingLookupTest {
    @Test void requiresExactlyOneSearchCriterionAndMasksPlate() {
        var repository = mock(PublicBookingLookupRepository.class);
        var lookup = new PublicBookingLookup(repository);
        assertEquals("LOOKUP_CRITERION_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> lookup.lookup(null, null, 0, 20)).getMessage());
        assertEquals("LOOKUP_CRITERION_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> lookup.lookup("0912345678", "BK-23456789ABCD", 0, 20)).getMessage());
        assertEquals("****-**3.45", PublicBookingLookup.maskPlate("59A1-123.45"));
        assertEquals("*", PublicBookingLookup.maskPlate("A"));
    }

    @Test void returnsOnlyTheProjectedLookupFieldsAndStablePageMetadata() {
        var repository = mock(PublicBookingLookupRepository.class);
        when(repository.findCustomerIdByPhone("+84912345678")).thenReturn(42L);
        when(repository.byCustomer(42, 1, 20)).thenReturn(new PublicBookingLookupRepository.LookupData(List.of(
                new PublicBookingLookupRepository.BookingData("BK-23456789ABCD",
                        OffsetDateTime.parse("2031-01-01T08:00:00Z"), OffsetDateTime.parse("2031-01-05T02:00:00Z"),
                        OffsetDateTime.parse("2031-01-05T03:00:00Z"), "CAR", "59A1-123.45", "CONFIRMED",
                        List.of(new PublicBookingLookupRepository.ServiceData("Thay dầu", 100_000)))), 21));
        var page = new PublicBookingLookup(repository).lookup("0912 345 678", null, 1, 20);
        assertEquals(2, page.totalPages());
        assertEquals(21, page.totalElements());
        assertEquals("****-**3.45", page.items().getFirst().maskedPlate());
        assertEquals(100_000, page.items().getFirst().referenceTotal());
        verify(repository).byCustomer(42, 1, 20);
    }
}
