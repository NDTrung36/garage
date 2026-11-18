package vn.garage.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.garage.catalog.application.ServiceCatalog;
import vn.garage.catalog.application.ServiceCatalog.ServiceView;

@RestController
public class ServiceController {
    private final ServiceCatalog catalog;

    public ServiceController(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    public record Create(@NotBlank String code, @NotBlank String name, @Size(max = 1000) String description,
                         @NotBlank String vehicleType, @NotNull BigDecimal referencePrice) { }

    public record Update(@NotBlank String name, @Size(max = 1000) String description, @NotBlank String vehicleType,
                         BigDecimal referencePrice, Boolean active, @NotNull @PositiveOrZero Long expectedVersion) { }

    @GetMapping("/api/public/services")
    public List<ServiceView> publicList(@RequestParam String vehicleType) {
        return catalog.publicList(vehicleType);
    }

    @GetMapping("/api/admin/services")
    public ServiceCatalog.ServicePage list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return catalog.adminList(page, size);
    }

    @PostMapping("/api/admin/services")
    public ResponseEntity<ServiceView> create(@Valid @RequestBody Create request) {
        return ResponseEntity.status(201).body(catalog.create(request.code(), request.name(), request.description(),
                request.vehicleType(), exactPrice(request.referencePrice())));
    }

    @PatchMapping("/api/admin/services/{id}")
    public ServiceView update(@PathVariable long id, @Valid @RequestBody Update request) {
        Long price = request.referencePrice() == null ? null : exactPrice(request.referencePrice());
        return catalog.update(id, request.name(), request.description(), request.vehicleType(),
                price, request.active(), request.expectedVersion());
    }

    @ExceptionHandler(ServiceCatalog.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("code", "SERVICE_NOT_FOUND", "message", "Không tìm thấy dịch vụ."));
    }

    @ExceptionHandler(ServiceCatalog.DuplicateCode.class)
    public ResponseEntity<Map<String, String>> duplicateCode() {
        return ResponseEntity.status(409).body(Map.of("code", "SERVICE_CODE_EXISTS", "message", "Mã dịch vụ đã được sử dụng."));
    }

    private static long exactPrice(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("INVALID_REFERENCE_PRICE");
        }
    }
}
