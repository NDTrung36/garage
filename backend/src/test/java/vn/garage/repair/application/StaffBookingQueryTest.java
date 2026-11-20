package vn.garage.repair.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.garage.repair.domain.BookingStore;

class StaffBookingQueryTest {
    @Test void mapsLocalDayToInclusiveStartAndExclusiveNextMidnight() {
        BookingStore store = mock(BookingStore.class);
        when(store.list(any(), any(), eq(BookingStore.BookingStatus.CONFIRMED), eq(0), eq(20)))
                .thenReturn(new BookingStore.BookingPage(List.of(), 0, 20, 0, 0));
        var query = new StaffBookingQuery(store, Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC));
        query.list(LocalDate.of(2031, 1, 6), "CONFIRMED", 0, 20);
        var from = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        var to = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(store).list(from.capture(), to.capture(), eq(BookingStore.BookingStatus.CONFIRMED), eq(0), eq(20));
        assertEquals(OffsetDateTime.parse("2031-01-06T00:00:00+07:00"), from.getValue());
        assertEquals(OffsetDateTime.parse("2031-01-07T00:00:00+07:00"), to.getValue());
    }

    @Test void defaultsToTodayAtGarageTimezoneAndRejectsUnboundedPages() {
        BookingStore store = mock(BookingStore.class);
        when(store.list(any(), any(), isNull(), eq(0), eq(20)))
                .thenReturn(new BookingStore.BookingPage(List.of(), 0, 20, 0, 0));
        var query = new StaffBookingQuery(store, Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC));
        query.list(null, null, 0, 20);
        verify(store).list(OffsetDateTime.parse("2031-01-06T00:00:00+07:00"),
                OffsetDateTime.parse("2031-01-07T00:00:00+07:00"), null, 0, 20);
        assertThrows(StaffBookingQuery.InvalidBookingQuery.class, () -> query.list(null, null, -1, 20));
        assertThrows(StaffBookingQuery.InvalidBookingQuery.class, () -> query.list(null, null, 0, 101));
    }
}
