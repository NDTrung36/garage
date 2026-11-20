package vn.garage.repair.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.customer.domain.ContactAndVehicle;
import vn.garage.repair.application.StaffBookingQuery;
import vn.garage.repair.application.VehicleIntakeService;
import vn.garage.support.idempotency.IdempotencyExecutor;

@RestController
public class StaffBookingsController {
    private final StaffBookingQuery query;
    private final VehicleIntakeService intake;

    public StaffBookingsController(StaffBookingQuery query, VehicleIntakeService intake) {
        this.query = query; this.intake = intake;
    }

    @GetMapping("/api/staff/bookings")
    public ResponseEntity<?> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(query.list(date, status, page == null ? 0 : page, size == null ? 20 : size));
    }

    @PostMapping("/api/staff/bookings/{id}/check-in")
    public ResponseEntity<VehicleIntakeService.CheckInResult> checkIn(@PathVariable String id,
            @Valid @RequestBody CheckInRequest request, Authentication authentication) {
        long bookingId = parseId(id);
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        var result = intake.checkIn(bookingId, request.expectedVersion(), request.odometerKm(),
                request.conditionNotes(), request.internalNotes(), actor.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/api/staff/walk-ins")
    public ResponseEntity<VehicleIntakeService.CheckInResult> walkIn(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody WalkInRequest request,
            Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        ContactAndVehicle contact = ContactAndVehicle.from(request.contact().fullName(), request.contact().phone(),
                request.vehicle().type(), request.vehicle().plateNumber(), request.vehicle().brand(), request.vehicle().model());
        List<Long> serviceIds = request.serviceIds().stream().map(StaffBookingsController::parseId).toList();
        var command = new VehicleIntakeService.WalkInCommand(contact, serviceIds, request.note(),
                request.odometerKm(), request.conditionNotes(), request.internalNotes());
        IdempotencyExecutor.Result<VehicleIntakeService.CheckInResult> result = intake.walkIn(command, key, actor.id());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new InvalidBookingId();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new InvalidBookingId(); }
    }

    public record CheckInRequest(@jakarta.validation.constraints.NotNull @Min(0) Long expectedVersion,
                                 @Min(0) Integer odometerKm,
                                 @Size(max = 2000) String conditionNotes,
                                 @Size(max = 2000) String internalNotes) { }

    public record ContactRequest(@NotBlank @Size(min = 2, max = 100) String fullName,
                                 @NotBlank @Size(max = 40) String phone) { }
    public record VehicleRequest(@NotBlank String type, @NotBlank @Size(max = 40) String plateNumber,
                                 @Size(max = 100) String brand, @Size(max = 100) String model) { }
    public record WalkInRequest(@NotNull @Valid ContactRequest contact,
                                @NotNull @Valid VehicleRequest vehicle,
                                @NotEmpty @Size(max = 50) List<@NotBlank String> serviceIds,
                                @Size(max = 1000) String note,
                                @Min(0) Integer odometerKm,
                                @Size(max = 2000) String conditionNotes,
                                @Size(max = 2000) String internalNotes) { }

    public static final class InvalidBookingId extends RuntimeException { }
}
