package vn.garage.repair.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.application.RepairPartQuery;

@RestController
public class RepairPartsController {
    private final RepairPartCommands commands;
    private final RepairPartQuery query;

    public RepairPartsController(RepairPartCommands commands, RepairPartQuery query) {
        this.commands = commands;
        this.query = query;
    }

    public record IssueRequest(@NotBlank String partId, @NotBlank String quantity) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) { throw new IllegalArgumentException("UNKNOWN_FIELD"); }
    }
    public record ReturnRequest(@NotBlank String originalIssueId, @NotBlank String quantity) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) { throw new IllegalArgumentException("UNKNOWN_FIELD"); }
    }
    public record PriceRequest(@NotNull @Min(0) Long unitPrice, @NotBlank @Size(max = 500) String reason,
                               @NotNull @Min(0) Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) { throw new IllegalArgumentException("UNKNOWN_FIELD"); }
    }

    @GetMapping("/api/staff/repair-orders/{id}/parts")
    public ResponseEntity<RepairPartQuery.Detail> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.detail(parseId(id, "INVALID_REPAIR_ORDER_ID")));
    }

    @PostMapping("/api/staff/repair-orders/{id}/part-issues")
    public ResponseEntity<RepairPartCommands.CommandResponse> issue(@PathVariable String id,
            @Valid @RequestBody IssueRequest request, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.issue(parseId(id, "INVALID_REPAIR_ORDER_ID"),
                parseId(request.partId(), "INVALID_PART_ID"), request.quantity(), key, actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    @PostMapping("/api/staff/repair-orders/{id}/part-returns")
    public ResponseEntity<RepairPartCommands.CommandResponse> returnPart(@PathVariable String id,
            @Valid @RequestBody ReturnRequest request, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.returnPart(parseId(id, "INVALID_REPAIR_ORDER_ID"),
                parseId(request.originalIssueId(), "INVALID_ISSUE_ID"), request.quantity(), key,
                actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }

    @PatchMapping("/api/staff/repair-orders/{id}/part-items/{itemId}/price")
    public ResponseEntity<RepairPartCommands.PriceResponse> updatePrice(@PathVariable String id,
            @PathVariable String itemId, @Valid @RequestBody PriceRequest request, Authentication authentication) {
        GaragePrincipal actor = (GaragePrincipal) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(commands.updatePrice(
                parseId(id, "INVALID_REPAIR_ORDER_ID"), parseId(itemId, "INVALID_PART_ITEM_ID"),
                request.unitPrice(), request.reason(), request.expectedVersion(), actor.id(), actor.role()));
    }

    private static long parseId(String value, String errorCode) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(errorCode);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(errorCode, invalid); }
    }
}
