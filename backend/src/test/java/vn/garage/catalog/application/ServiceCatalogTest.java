package vn.garage.catalog.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.garage.catalog.domain.BookingServiceLookup;

class ServiceCatalogTest {
    @Test
    void mapsCompatibleServiceRowsToApplicationSnapshots() {
        var lookup = mock(BookingServiceLookup.class);
        when(lookup.findActiveCompatible(List.of(8L, 3L), "CAR")).thenReturn(List.of(
                new BookingServiceLookup.ServiceSelection(3, "Rửa xe", 50_000),
                new BookingServiceLookup.ServiceSelection(8, "Thay dầu", 120_000)));
        var catalog = new ServiceCatalog(mock(JdbcTemplate.class), lookup);

        assertEquals(List.of(new BookingServiceCatalog.ServiceSnapshot(3, "Rửa xe", 50_000),
                        new BookingServiceCatalog.ServiceSnapshot(8, "Thay dầu", 120_000)),
                catalog.activeForBooking(List.of(8L, 3L), "CAR"));
        verify(lookup).findActiveCompatible(List.of(8L, 3L), "CAR");
    }

    @Test
    void rejectsEmptyDuplicateInvalidAndVehicleTypeSelection() {
        var lookup = mock(BookingServiceLookup.class);
        var catalog = new ServiceCatalog(mock(JdbcTemplate.class), lookup);

        assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                () -> catalog.activeForBooking(List.of(), "CAR")).getMessage());
        assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                () -> catalog.activeForBooking(List.of(1L, 1L), "CAR")).getMessage());
        assertEquals("INVALID_SERVICE_IDS", assertThrows(IllegalArgumentException.class,
                () -> catalog.activeForBooking(List.of(0L), "CAR")).getMessage());
        assertEquals("INVALID_VEHICLE_TYPE", assertThrows(IllegalArgumentException.class,
                () -> catalog.activeForBooking(List.of(1L), "TRUCK")).getMessage());
        verifyNoInteractions(lookup);
    }

    @Test
    void rejectsInactiveOrIncompatibleSelectionWhenLookupReturnsFewerRows() {
        var lookup = mock(BookingServiceLookup.class);
        when(lookup.findActiveCompatible(List.of(1L, 2L), "CAR"))
                .thenReturn(List.of(new BookingServiceLookup.ServiceSelection(1, "Dịch vụ xe hơi", 100)));
        var catalog = new ServiceCatalog(mock(JdbcTemplate.class), lookup);

        assertEquals("SERVICE_NOT_AVAILABLE", assertThrows(IllegalArgumentException.class,
                () -> catalog.activeForBooking(List.of(1L, 2L), "CAR")).getMessage());
    }
}
