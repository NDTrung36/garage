package vn.garage.inventory.api;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.inventory.application.PartCatalog;
import vn.garage.inventory.domain.PartPage;
import vn.garage.inventory.domain.PartView;

@RestController
public class PartController {
    private final PartCatalog catalog;

    public PartController(PartCatalog catalog) {
        this.catalog = catalog;
    }

    public record CreateRequest(@NotNull String code, @NotNull String name, @NotNull String unit,
                                @NotNull String vehicleType, @NotNull BigDecimal referencePrice) {
        @JsonAnySetter
        public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("UNKNOWN_FIELD");
        }
    }

    public record UpdateRequest(String name, String unit, String vehicleType, BigDecimal referencePrice,
                                Boolean active, @NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter
        public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("UNKNOWN_FIELD");
        }
    }

    @GetMapping("/api/staff/parts")
    public ResponseEntity<PartPage> staffList(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Boolean active,
                                         @RequestParam(required = false) String vehicleType) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.list(page, size, q, active, vehicleType));
    }

    @GetMapping("/api/admin/parts")
    public ResponseEntity<PartPage> adminList(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Boolean active,
                                         @RequestParam(required = false) String vehicleType) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.list(page, size, q, active, vehicleType));
    }

    @PostMapping("/api/admin/parts")
    public ResponseEntity<PartView> create(@Valid @RequestBody CreateRequest request,
                                                       Authentication authentication) {
        var actor = (GaragePrincipal) authentication.getPrincipal();
        long price = exactPrice(request.referencePrice());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(catalog.create(request.code(), request.name(), request.unit(),
                request.vehicleType(), price, actor.role(), actor.id()));
    }

    @PatchMapping("/api/admin/parts/{id}")
    public ResponseEntity<PartView> update(@PathVariable long id, @Valid @RequestBody UpdateRequest request,
                                       Authentication authentication) {
        var actor = (GaragePrincipal) authentication.getPrincipal();
        Long price = request.referencePrice() == null ? null : exactPrice(request.referencePrice());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.update(id, request.name(), request.unit(), request.vehicleType(), price,
                request.active(), request.expectedVersion(), actor.role(), actor.id()));
    }

    private static long exactPrice(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("INVALID_REFERENCE_PRICE");
        }
    }
}
