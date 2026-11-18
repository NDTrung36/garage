package vn.garage.repair.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SchedulePolicy {
    private SchedulePolicy() { }

    public static void validate(int slotMinutes, int lead, int horizon, int carCapacity,
                                int bikeCapacity, Map<Integer, List<LocalTime[]>> hours) {
        if (slotMinutes < 15 || slotMinutes > 240 || slotMinutes % 15 != 0
                || lead < 1 || lead > 10_080 || horizon < 1 || horizon > 90
                || carCapacity < 0 || carCapacity > 100 || bikeCapacity < 0 || bikeCapacity > 100) {
            throw new IllegalArgumentException("INVALID_SCHEDULE_SETTINGS");
        }
        for (var day : hours.entrySet()) {
            if (day.getKey() < 1 || day.getKey() > 7) throw new IllegalArgumentException("INVALID_WEEKDAY");
            for (var span : day.getValue()) {
                if (span.length != 2 || !minuteAligned(span[0]) || !minuteAligned(span[1])
                        || !span[0].isBefore(span[1])
                        || Duration.between(span[0], span[1]).toMinutes() % slotMinutes != 0) {
                    throw new IllegalArgumentException("INVALID_WORKING_HOURS");
                }
            }
            var sorted = day.getValue().stream().sorted(Comparator.comparing(span -> span[0])).toList();
            LocalTime previousEnd = null;
            for (var span : sorted) {
                if (previousEnd != null && span[0].isBefore(previousEnd)) {
                    throw new IllegalArgumentException("INVALID_WORKING_HOURS");
                }
                previousEnd = span[1];
            }
        }
    }

    private static boolean minuteAligned(LocalTime time) {
        return time != null && time.getSecond() == 0 && time.getNano() == 0;
    }
}
