package vn.garage.repair.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SlotPlan {
    private SlotPlan() { }

    public record Hours(int dayOfWeek, java.time.LocalTime opensAt, java.time.LocalTime closesAt) { }

    public record SlotTime(OffsetDateTime startsAt, OffsetDateTime endsAt, String vehicleType) {
        boolean overlaps(SlotTime other) {
            return vehicleType.equals(other.vehicleType) && startsAt.isBefore(other.endsAt)
                    && other.startsAt.isBefore(endsAt);
        }

        SlotTime canonical() {
            return new SlotTime(startsAt.withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    endsAt.withOffsetSameInstant(java.time.ZoneOffset.UTC), vehicleType);
        }
    }

    public record Existing(long id, SlotTime time, int capacity, boolean closed, boolean manualClosed,
                           Integer capacityOverride, boolean hasBooking) { }
    public record Insert(SlotTime time, int capacity) { }
    public record Change(long id, int capacity, boolean closed) { }
    public record Changes(List<Long> deletes, List<Change> updates, List<Insert> inserts) { }

    public static Changes reconcile(OffsetDateTime now, LocalDate through, ZoneId zone, int minutes,
                                    int carCapacity, int bikeCapacity, List<Hours> hours, Set<LocalDate> closedDates,
                                    List<Existing> existing) {
        Map<SlotTime, Insert> desired = new java.util.LinkedHashMap<>();
        for (LocalDate date = now.toLocalDate(); !date.isAfter(through); date = date.plusDays(1)) {
            if (closedDates.contains(date)) continue;
            for (Hours period : hours) {
                if (period.dayOfWeek() != date.getDayOfWeek().getValue()) continue;
                int count = Math.toIntExact(Duration.between(period.opensAt(), period.closesAt()).toMinutes() / minutes);
                var start = date.atTime(period.opensAt()).atZone(zone).toOffsetDateTime();
                for (int i = 0; i < count; i++, start = start.plusMinutes(minutes)) {
                    addDesired(desired, now, start, minutes, "CAR", carCapacity);
                    addDesired(desired, now, start, minutes, "MOTORBIKE", bikeCapacity);
                }
            }
        }

        Map<LocalDate, List<SlotTime>> retained = new HashMap<>();
        for (Existing slot : existing) {
            if (!slot.time().startsAt().isAfter(now) || slot.hasBooking()
                    || slot.manualClosed() || slot.capacityOverride() != null) {
                LocalDate date = slot.time().startsAt().atZoneSameInstant(zone).toLocalDate();
                retained.computeIfAbsent(date, ignored -> new ArrayList<>()).add(slot.time());
            }
        }

        List<Long> deletes = new ArrayList<>();
        List<Change> updates = new ArrayList<>();
        for (Existing slot : existing) {
            if (!slot.time().startsAt().isAfter(now)) continue;
            var match = desired.remove(slot.time().canonical());
            boolean pinned = slot.hasBooking() || slot.manualClosed() || slot.capacityOverride() != null;
            if (!pinned && (match == null || overlapsRetained(match.time(), zone, retained))) {
                deletes.add(slot.id());
                continue;
            }
            boolean closed = slot.manualClosed() || match == null;
            int capacity = slot.capacityOverride() != null ? slot.capacityOverride() : slot.capacity();
            if (!slot.hasBooking() && slot.capacityOverride() == null && match != null) capacity = match.capacity();
            if (slot.closed() != closed || slot.capacity() != capacity) {
                updates.add(new Change(slot.id(), capacity, closed));
            }
        }

        List<Insert> inserts = desired.values().stream()
                .filter(row -> !overlapsRetained(row.time(), zone, retained)).toList();
        return new Changes(deletes, updates, inserts);
    }

    private static void addDesired(Map<SlotTime, Insert> desired, OffsetDateTime now, OffsetDateTime start,
                                   int minutes, String vehicleType, int capacity) {
        if (!start.isAfter(now)) return;
        var time = new SlotTime(start, start.plusMinutes(minutes), vehicleType);
        desired.put(time.canonical(), new Insert(time, capacity));
    }

    private static boolean overlapsRetained(SlotTime time, ZoneId zone, Map<LocalDate, List<SlotTime>> retained) {
        var date = time.startsAt().atZoneSameInstant(zone).toLocalDate();
        return retained.getOrDefault(date, List.of()).stream().anyMatch(time::overlaps);
    }
}
