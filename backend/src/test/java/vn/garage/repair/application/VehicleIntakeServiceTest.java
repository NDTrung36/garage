package vn.garage.repair.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import vn.garage.repair.domain.BookingSlotRepository;
import vn.garage.repair.domain.BookingStore;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.customer.application.CustomerVehicleOnboarding;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.transaction.SqlServerWriteTransactions;

class VehicleIntakeServiceTest {
    private BookingStore bookings;
    private BookingSlotRepository slots;
    private RepairOrderStore orders;
    private SqlServerWriteTransactions writes;
    private VehicleIntakeService service;

    @BeforeEach void setUp() {
        bookings = mock(BookingStore.class); slots = mock(BookingSlotRepository.class);
        orders = mock(RepairOrderStore.class); writes = mock(SqlServerWriteTransactions.class);
        when(writes.execute(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        service = new VehicleIntakeService(bookings, slots, orders, writes,
                Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC));
    }

    @Test void locksSlotThenBookingCreatesOneOrderAndKeepsExistingReservation() {
        when(bookings.readCheckInHint(7)).thenReturn(Optional.of(new BookingStore.BookingRef(7, 11L)));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.CONFIRMED, 2)));
        when(orders.findByBookingForUpdate(7)).thenReturn(Optional.empty());
        when(orders.create(any())).thenAnswer(invocation -> {
            var input = (RepairOrderStore.NewRepairOrder) invocation.getArgument(0);
            return new RepairOrderStore.RepairOrder(23, 7, RepairStatus.IN_PROGRESS, 0, input.receivedBy(),
                    input.receivedAt(), input.odometerKm(), input.conditionNotes(), input.internalNotes());
        });
        when(bookings.markCheckedIn(eq(7L), eq(2L), any())).thenReturn(true);

        var result = service.checkIn(7, 2, 42, "  Đèn vỡ  ", "  ", 41);
        assertEquals("23", result.repairOrderId());
        assertEquals(BookingStore.BookingStatus.CHECKED_IN, result.bookingStatus());
        assertEquals(OffsetDateTime.parse("2031-01-06T01:00:00Z"), result.receivedAt());
        var order = org.mockito.ArgumentCaptor.forClass(RepairOrderStore.NewRepairOrder.class);
        verify(orders).create(order.capture());
        assertEquals(41, order.getValue().receivedBy());
        assertEquals(42, order.getValue().odometerKm());
        assertEquals("Đèn vỡ", order.getValue().conditionNotes());
        assertNull(order.getValue().internalNotes());
        InOrder orderOfLocks = inOrder(slots, bookings);
        orderOfLocks.verify(bookings).readCheckInHint(7);
        orderOfLocks.verify(slots).lockForBooking(11);
        orderOfLocks.verify(bookings).lockForCheckIn(7);
        verifyNoMoreInteractions(slots);
    }

    @Test void replayReturnsSameOrderBeforeVersionCheckAndPreservesSnapshot() {
        when(bookings.readCheckInHint(7)).thenReturn(Optional.of(new BookingStore.BookingRef(7, 11L)));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.CHECKED_IN, 3)));
        var existing = new RepairOrderStore.RepairOrder(23, 7, RepairStatus.IN_PROGRESS, 0, 41,
                OffsetDateTime.parse("2031-01-06T01:00:00Z"), 10, "old", null);
        when(orders.findByBookingForUpdate(7)).thenReturn(Optional.of(existing));
        var replay = service.checkIn(7, 0, 999, "new", null, 99);
        assertEquals("23", replay.repairOrderId());
        assertEquals(existing.receivedAt(), replay.receivedAt());
        verify(orders, never()).create(any());
        verify(bookings, never()).markCheckedIn(anyLong(), anyLong(), any());
    }

    @Test void completedBookingAlsoReplaysItsCompletedOrder() {
        when(bookings.readCheckInHint(7)).thenReturn(Optional.of(new BookingStore.BookingRef(7, 11L)));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.COMPLETED, 5)));
        when(orders.findByBookingForUpdate(7)).thenReturn(Optional.of(new RepairOrderStore.RepairOrder(23, 7,
                RepairStatus.COMPLETED, 4, 41, OffsetDateTime.parse("2031-01-06T01:00:00Z"), null, null, null)));
        var replay = service.checkIn(7, 0, null, null, null, 99);
        assertEquals(BookingStore.BookingStatus.COMPLETED, replay.bookingStatus());
        assertEquals(RepairStatus.COMPLETED, replay.repairStatus());
        assertEquals("23", replay.repairOrderId());
        verify(orders, never()).create(any());
    }

    @Test void missingOrderAfterCheckInIsInvariantAndCancelledIsRejected() {
        when(bookings.readCheckInHint(7)).thenReturn(Optional.of(new BookingStore.BookingRef(7, 11L)));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.CHECKED_IN, 3)));
        when(orders.findByBookingForUpdate(7)).thenReturn(Optional.empty());
        assertThrows(VehicleIntakeService.RepairInvariantViolation.class, () -> service.checkIn(7, 3, null, null, null, 41));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.CANCELLED, 3)));
        assertThrows(VehicleIntakeService.BookingStateConflict.class, () -> service.checkIn(7, 3, null, null, null, 41));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.NO_SHOW, 3)));
        assertThrows(VehicleIntakeService.BookingStateConflict.class, () -> service.checkIn(7, 3, null, null, null, 41));
    }

    @Test void staleVersionAndInvalidIntakeDoNotCreateOrder() {
        when(bookings.readCheckInHint(7)).thenReturn(Optional.of(new BookingStore.BookingRef(7, 11L)));
        when(bookings.lockForCheckIn(7)).thenReturn(Optional.of(booking(BookingStore.BookingStatus.CONFIRMED, 4)));
        assertThrows(VehicleIntakeService.StaleBookingVersion.class, () -> service.checkIn(7, 3, null, null, null, 41));
        assertThrows(VehicleIntakeService.InvalidIntakeInput.class, () -> service.checkIn(7, 3, -1, null, null, 41));
        verify(orders, never()).create(any());
    }

    @Test void bookingCodeCollisionRetriesWithFreshCodeAndNewIdempotencyAttempt() {
        var idempotency = mock(IdempotencyExecutor.class);
        var customers = mock(CustomerVehicleOnboarding.class);
        var services = mock(BookingServiceCatalog.class);
        var contact = ContactAndVehicle.from("Nguyễn Văn An", "0912345678", "CAR", "51A-12345", null, null);
        var command = new VehicleIntakeService.WalkInCommand(contact, List.of(17L), null, 120,
                null, null);
        when(services.activeForBooking(List.of(17L), "CAR"))
                .thenReturn(List.of(new BookingServiceCatalog.ServiceSnapshot(17, "Thay dầu", 100_000)));
        when(customers.obtain(contact)).thenReturn(new CustomerVehicleOnboarding.OnboardedVehicle(3, 4, contact));
        when(bookings.createWalkIn(any(), anyList()))
                .thenThrow(new BookingStore.BookingCodeCollisionException(new IllegalStateException("UX_bookings_code")))
                .thenReturn(7L);
        when(orders.create(any())).thenReturn(new RepairOrderStore.RepairOrder(22, 7,
                RepairStatus.IN_PROGRESS, 0, 41, OffsetDateTime.parse("2031-01-06T01:00:00Z"),
                120, null, null));
        when(idempotency.<VehicleIntakeService.CheckInResult>execute(eq("repair.walkin.create"), eq(41L),
                eq("same-key"), any(), eq(VehicleIntakeService.CheckInResult.class), any()))
                .thenAnswer(invocation -> {
                    var commandWork = (java.util.function.Supplier<? extends IdempotencyExecutor.CommandResult<VehicleIntakeService.CheckInResult>>) invocation.getArgument(5);
                    var response = commandWork.get();
                    return new IdempotencyExecutor.Result<>(response.httpStatus(), response.body(), false);
                });
        var generated = new java.util.concurrent.atomic.AtomicInteger();
        var intake = new VehicleIntakeService(bookings, slots, orders, writes,
                Clock.fixed(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC), idempotency,
                customers, services, () -> generated.getAndIncrement() == 0 ? "BK-AAAAAAAAAAAA" : "BK-BBBBBBBBBBBB");

        var created = intake.walkIn(command, "same-key", 41);
        assertEquals(201, created.httpStatus());
        assertEquals("BK-BBBBBBBBBBBB", created.body().bookingCode());
        assertEquals("22", created.body().repairOrderId());
        verify(idempotency, times(2)).execute(eq("repair.walkin.create"), eq(41L), eq("same-key"),
                any(), eq(VehicleIntakeService.CheckInResult.class), any());
        var captured = org.mockito.ArgumentCaptor.forClass(BookingStore.NewWalkInBooking.class);
        verify(bookings, times(2)).createWalkIn(captured.capture(), anyList());
        assertEquals("BK-AAAAAAAAAAAA", captured.getAllValues().get(0).code());
        assertEquals("BK-BBBBBBBBBBBB", captured.getAllValues().get(1).code());
        verify(orders, times(1)).create(any());
    }

    private static BookingStore.LockedBooking booking(BookingStore.BookingStatus status, long version) {
        return new BookingStore.LockedBooking(7, "BK-7", "ONLINE", status, 11L, version);
    }
}
