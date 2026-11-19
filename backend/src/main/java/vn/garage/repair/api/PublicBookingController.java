package vn.garage.repair.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.garage.repair.application.GuestBookingService;
import vn.garage.repair.application.PublicBookingLookup;
import vn.garage.repair.domain.GuestBookingRequest;
import vn.garage.support.idempotency.IdempotencyExecutor.InvalidIdempotencyKeyException;

@RestController
@RequestMapping("/api/public")
public class PublicBookingController {
    private static final Pattern UUID_KEY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final GuestBookingService bookings;
    private final PublicBookingLookup lookups;

    public PublicBookingController(GuestBookingService bookings, PublicBookingLookup lookups) {
        this.bookings = bookings; this.lookups = lookups;
    }

    @PostMapping("/bookings")
    public ResponseEntity<GuestBookingService.BookingResult> create(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateBookingBody body) {
        if (!UUID_KEY.matcher(key).matches()) throw new InvalidIdempotencyKeyException();
        List<Long> serviceIds;
        try { serviceIds = body.serviceIds().stream().map(PublicBookingController::positiveId).toList(); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("INVALID_SERVICE_IDS"); }
        long slotId;
        try { slotId = positiveId(body.slotId()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("INVALID_SLOT_ID"); }
        GuestBookingRequest request = GuestBookingRequest.create(body.contact().fullName(), body.contact().phone(),
                body.vehicle().type(), body.vehicle().plateNumber(), body.vehicle().brand(), body.vehicle().model(),
                serviceIds, slotId, body.note());
        return ResponseEntity.status(201).body(bookings.create(request, key));
    }

    @PostMapping("/lookups")
    public ResponseEntity<PublicBookingLookup.LookupPage> lookup(@Valid @RequestBody LookupBody body) {
        var page = lookups.lookup(body.phone(), body.bookingCode(), body.page() == null ? 0 : body.page(),
                body.size() == null ? 20 : body.size());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    private static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
        long result = Long.parseLong(value);
        if (result <= 0) throw new NumberFormatException();
        return result;
    }

    public record ContactBody(@NotBlank @Size(max = 100) String fullName,
                              @NotBlank @Size(max = 40) String phone) { }
    public record VehicleBody(@NotBlank String type, @NotBlank @Size(max = 40) String plateNumber,
                              @Size(max = 100) String brand, @Size(max = 100) String model) { }
    public record CreateBookingBody(@jakarta.validation.constraints.NotNull @Valid ContactBody contact,
                                    @jakarta.validation.constraints.NotNull @Valid VehicleBody vehicle,
                                    @NotEmpty @Size(max = 50) List<@NotBlank String> serviceIds,
                                    @NotBlank String slotId, @Size(max = 1000) String note) { }
    public record LookupBody(String phone, String bookingCode,
                             @jakarta.validation.constraints.Min(0) Integer page,
                             @jakarta.validation.constraints.Min(1) Integer size) { }
}
