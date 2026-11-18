package vn.garage.repair.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class SchedulePolicyTest {
    @Test void acceptsDefaultPeriodsAndSettings() {
        var hours=Map.of(1,List.of(new LocalTime[]{LocalTime.of(8,0),LocalTime.NOON},new LocalTime[]{LocalTime.of(13,0),LocalTime.of(17,0)}));
        assertDoesNotThrow(()->SchedulePolicy.validate(60,60,14,1,2,hours));
    }
    @Test void rejectsOverlappingAndMisalignedHours() {
        var overlap=Map.of(1,List.of(new LocalTime[]{LocalTime.of(8,0),LocalTime.of(10,0)},new LocalTime[]{LocalTime.of(9,0),LocalTime.NOON}));
        assertThrows(IllegalArgumentException.class,()->SchedulePolicy.validate(60,60,14,1,2,overlap));
        var misaligned=Map.of(1,Collections.singletonList(new LocalTime[]{LocalTime.of(8,0),LocalTime.of(9,30)}));
        assertThrows(IllegalArgumentException.class,()->SchedulePolicy.validate(60,60,14,1,2,misaligned));
    }
    @Test void rejectsInvalidSlotSettings() {
        assertThrows(IllegalArgumentException.class,()->SchedulePolicy.validate(14,60,14,1,2,Map.of()));
        assertThrows(IllegalArgumentException.class,()->SchedulePolicy.validate(60,0,14,1,2,Map.of()));
        assertThrows(IllegalArgumentException.class,()->SchedulePolicy.validate(60,60,91,1,2,Map.of()));
    }

    @Test void rejectsPeriodsWithSeconds() {
        var hours = Map.of(1, Collections.singletonList(new LocalTime[]{LocalTime.of(20,0), LocalTime.of(23,0,30)}));
        assertThrows(IllegalArgumentException.class, () -> SchedulePolicy.validate(60,60,14,1,2,hours));
    }
}
