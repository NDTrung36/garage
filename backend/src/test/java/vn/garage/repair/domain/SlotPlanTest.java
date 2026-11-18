package vn.garage.repair.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlotPlanTest {
    private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2031-01-06T07:00:00+07:00");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void eveningShiftStopsAtClosingTime() {
        var changes = SlotPlan.reconcile(MONDAY, MONDAY.toLocalDate(), ZONE, 60, 1, 2,
                List.of(new SlotPlan.Hours(1, LocalTime.of(20, 0), LocalTime.of(23, 0))), Set.of(), List.of());
        assertEquals(6, changes.inserts().size());
        assertEquals(LocalTime.of(23, 0), changes.inserts().getLast().time().endsAt().toLocalTime());
        assertTrue(changes.inserts().stream().allMatch(slot -> slot.time().endsAt().toLocalDate().equals(MONDAY.toLocalDate())));
    }

    @Test
    void retainedBookingBlocksOverlappingSlotsForItsVehicleType() {
        var starts = MONDAY.withHour(8);
        var time = new SlotPlan.SlotTime(starts, starts.plusHours(1), "CAR");
        var booked = new SlotPlan.Existing(10, time, 3, false, false, null, true);
        var disposable = new SlotPlan.Existing(11,
                new SlotPlan.SlotTime(starts, starts.plusMinutes(30), "CAR"), 1, false, false, null, false);
        var changes = SlotPlan.reconcile(MONDAY, MONDAY.toLocalDate(), ZONE, 30, 1, 2,
                List.of(new SlotPlan.Hours(1, LocalTime.of(8, 0), LocalTime.of(10, 0))), Set.of(),
                List.of(booked, disposable));
        assertEquals(List.of(11L), changes.deletes());
        assertEquals(List.of(new SlotPlan.Change(10, 3, true)), changes.updates());
        assertEquals(6, changes.inserts().size());
        assertTrue(changes.inserts().stream().noneMatch(slot -> slot.time().overlaps(time)));
    }

    @Test
    void manualOverrideSurvivesHolidayAndAnOffsetChangeInStorage() {
        var starts = MONDAY.withHour(8).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        var time = new SlotPlan.SlotTime(starts, starts.plusHours(1), "CAR");
        var manual = new SlotPlan.Existing(12, time, 4, true, true, 4, false);
        var hours = List.of(new SlotPlan.Hours(1, LocalTime.of(8, 0), LocalTime.of(9, 0)));
        var during = SlotPlan.reconcile(MONDAY, MONDAY.toLocalDate(), ZONE, 60, 1, 2, hours,
                Set.of(MONDAY.toLocalDate()), List.of(manual));
        assertEquals(new SlotPlan.Changes(List.of(), List.of(), List.of()), during);
        var reopened = SlotPlan.reconcile(MONDAY, MONDAY.toLocalDate(), ZONE, 60, 1, 2, hours, Set.of(), List.of(manual));
        assertTrue(reopened.deletes().isEmpty());
        assertTrue(reopened.updates().isEmpty());
        assertEquals(List.of("MOTORBIKE"), reopened.inserts().stream().map(s -> s.time().vehicleType()).toList());
    }
}
