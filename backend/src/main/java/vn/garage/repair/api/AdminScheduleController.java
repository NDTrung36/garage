package vn.garage.repair.api;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.garage.repair.application.SlotGenerator;
import vn.garage.repair.application.SlotGenerator.*;
import vn.garage.repair.domain.SlotPlan.Hours;

@RestController
@RequestMapping("/api/admin")
public class AdminScheduleController {
    private final SlotGenerator slots;

    public AdminScheduleController(SlotGenerator slots) {
        this.slots = slots;
    }

    public record SettingsRequest(@NotNull Integer slotMinutes, @NotNull Integer minimumLeadMinutes,
                                  @NotNull Integer maximumAdvanceDays, @NotNull Integer carCapacity,
                                  @NotNull Integer motorbikeCapacity, @NotNull List<Hours> workingHours,
                                  @NotNull @PositiveOrZero Long expectedVersion) { }
    public record ClosedDateRequest(@NotNull LocalDate date, @NotBlank String reason,
                                    @NotNull @PositiveOrZero Long expectedSettingsVersion) { }
    public record OverrideRequest(@NotNull Boolean manualClosed, Integer capacityOverride,
                                   @NotNull @PositiveOrZero Long expectedVersion) { }
    public record GenerationResult(LocalDate generatedThrough) { }

    @GetMapping("/schedule-settings")
    public ScheduleSettings settings() {
        return slots.scheduleSettings();
    }

    @PutMapping("/schedule-settings")
    public ScheduleSettings updateSettings(@Valid @RequestBody SettingsRequest request) {
        var values = new Settings(request.slotMinutes(), request.minimumLeadMinutes(), request.maximumAdvanceDays(),
                request.carCapacity(), request.motorbikeCapacity(), null, request.expectedVersion());
        return slots.updateSettings(values, request.workingHours(), request.expectedVersion());
    }

    @GetMapping("/closed-dates")
    public List<ClosedDate> closedDates(@RequestParam LocalDate from, @RequestParam LocalDate through) {
        return slots.closedDates(from, through);
    }

    @PostMapping("/closed-dates")
    public ResponseEntity<ClosedDate> addClosedDate(@Valid @RequestBody ClosedDateRequest request) {
        return ResponseEntity.status(201).body(
                slots.addClosedDate(request.date(), request.reason(), request.expectedSettingsVersion()));
    }

    @DeleteMapping("/closed-dates/{id}")
    public ResponseEntity<Void> removeClosedDate(@PathVariable long id, @RequestParam long expectedSettingsVersion) {
        slots.removeClosedDate(id, expectedSettingsVersion);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/slots")
    public List<AdminSlot> slots(@RequestParam LocalDate date, @RequestParam String vehicleType) {
        return slots.adminSlots(date, vehicleType);
    }

    @PatchMapping("/slots/{id}")
    public ResponseEntity<AdminSlot> updateSlot(@PathVariable long id, @Valid @RequestBody OverrideRequest request) {
        var slot = slots.updateSlot(id, request.manualClosed(), request.capacityOverride(), request.expectedVersion());
        return slot == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(slot);
    }

    @PostMapping("/schedule/regenerate")
    public GenerationResult regenerate() {
        return new GenerationResult(slots.regenerate());
    }
}
