package vn.garage.inventory.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.inventory.application.StockCommands;

@RestController
public class AdminStockController {
    private final StockCommands commands;

    public AdminStockController(StockCommands commands) { this.commands = commands; }

    public record ReceiptRequest(@NotBlank String partId, @NotBlank String quantity,
                                 @NotBlank @Size(max = 500) String reason, @Size(max = 500) String note) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("UNKNOWN_FIELD");
        }
    }

    public record AdjustmentRequest(@NotBlank String partId, @NotBlank String quantityDelta,
                                    @NotBlank @Size(max = 500) String reason) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("UNKNOWN_FIELD");
        }
    }

    @PostMapping("/api/admin/stock-receipts")
    public ResponseEntity<StockCommands.CommandResponse> receipt(@Valid @RequestBody ReceiptRequest request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.receipt(parseId(request.partId()), request.quantity(), request.reason(), request.note(),
                key, actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    @PostMapping("/api/admin/stock-adjustments")
    public ResponseEntity<StockCommands.CommandResponse> adjust(@Valid @RequestBody AdjustmentRequest request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.adjust(parseId(request.partId()), request.quantityDelta(), request.reason(),
                key, actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("INVALID_PART_ID");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("INVALID_PART_ID", invalid);
        }
    }
}
