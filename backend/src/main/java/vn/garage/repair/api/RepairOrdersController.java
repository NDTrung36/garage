package vn.garage.repair.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.repair.application.RepairOrderQuery;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.repair.application.RepairOrderService;

@RestController
public class RepairOrdersController {
    private final RepairOrderQuery query;
    private final RepairOrderService commands;
    private final RepairCompletionService completion;

    public RepairOrdersController(RepairOrderQuery query, RepairOrderService commands, RepairCompletionService completion) {
        this.query = query; this.commands = commands;
        this.completion = completion;
    }

    @PostMapping("/api/staff/repair-orders/{id}/complete")
    public ResponseEntity<RepairCompletionService.Result> complete(@PathVariable String id, @RequestBody JsonNode body,
                                                                   Authentication authentication) {
        ObjectNode object = object(body);
        rejectUnknown(object, "expectedVersion");
        GaragePrincipal actor = principal(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(completion.complete(
                parseId(id, "INVALID_REPAIR_ORDER_ID"), requiredLong(object, "expectedVersion"), actor.id(), actor.role()));
    }

    @GetMapping("/api/staff/repair-orders")
    public ResponseEntity<RepairOrderQuery.Page> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(query.list(status, page == null ? 0 : page, size == null ? 20 : size));
    }

    @GetMapping("/api/staff/repair-orders/{id}")
    public ResponseEntity<RepairOrderQuery.Detail> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.detail(parseId(id, "INVALID_REPAIR_ORDER_ID")));
    }

    @PatchMapping("/api/staff/repair-orders/{id}")
    public ResponseEntity<RepairOrderQuery.Detail> patchHeader(@PathVariable String id,
            @RequestBody JsonNode body, Authentication authentication) {
        long orderId = parseId(id, "INVALID_REPAIR_ORDER_ID");
        ObjectNode object = object(body);
        rejectUnknown(object, "expectedVersion", "odometerKm", "conditionNotes", "internalNotes");
        long version = requiredLong(object, "expectedVersion");
        RepairOrderService.HeaderPatch patch = new RepairOrderService.HeaderPatch(
                object.has("odometerKm"), optionalInteger(object, "odometerKm"),
                object.has("conditionNotes"), optionalText(object, "conditionNotes"),
                object.has("internalNotes"), optionalText(object, "internalNotes"));
        GaragePrincipal actor = principal(authentication);
        commands.patchHeader(orderId, version, patch, actor.id(), actor.role());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.detail(orderId));
    }

    @PostMapping("/api/staff/repair-orders/{id}/labor-items")
    public ResponseEntity<RepairOrderService.LaborResult> addLabor(@PathVariable String id,
            @Valid @RequestBody LaborCreateRequest request, Authentication authentication) {
        GaragePrincipal actor = principal(authentication);
        var result = commands.addLabor(parseId(id, "INVALID_REPAIR_ORDER_ID"),
                request.serviceId() == null ? null : parseId(request.serviceId(), "INVALID_SERVICE_ID"),
                request.description(), request.quantity(), request.unitPrice(), request.expectedVersion(),
                actor.id(), actor.role());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result);
    }

    @PatchMapping("/api/staff/repair-orders/{id}/labor-items/{itemId}")
    public ResponseEntity<RepairOrderService.LaborResult> patchLabor(@PathVariable String id,
            @PathVariable String itemId, @RequestBody JsonNode body, Authentication authentication) {
        ObjectNode object = object(body);
        rejectUnknown(object, "expectedVersion", "description", "quantity", "unitPrice");
        long version = requiredLong(object, "expectedVersion");
        var patch = new RepairOrderService.LaborPatch(version,
                object.has("description"), optionalText(object, "description"),
                object.has("quantity"), optionalText(object, "quantity"),
                object.has("unitPrice"), optionalLong(object, "unitPrice"));
        GaragePrincipal actor = principal(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(commands.updateLabor(
                parseId(id, "INVALID_REPAIR_ORDER_ID"), parseId(itemId, "INVALID_LABOR_ITEM_ID"),
                patch, actor.id(), actor.role()));
    }

    @DeleteMapping("/api/staff/repair-orders/{id}/labor-items/{itemId}")
    public ResponseEntity<Void> deleteLabor(@PathVariable String id, @PathVariable String itemId,
            @RequestParam long expectedVersion, Authentication authentication) {
        GaragePrincipal actor = principal(authentication);
        commands.voidLabor(parseId(id, "INVALID_REPAIR_ORDER_ID"), parseId(itemId, "INVALID_LABOR_ITEM_ID"),
                expectedVersion, actor.id(), actor.role());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ObjectNode object(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("INVALID_REQUEST_BODY");
        return (ObjectNode) body;
    }

    private static void rejectUnknown(ObjectNode object, String... allowed) {
        var valid = java.util.Set.of(allowed);
        object.fieldNames().forEachRemaining(name -> {
            if (!valid.contains(name)) throw new IllegalArgumentException("UNKNOWN_FIELD");
        });
    }

    private static long requiredLong(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase(java.util.Locale.ROOT));
        return value.longValue();
    }

    private static Long optionalLong(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase(java.util.Locale.ROOT));
        return value.longValue();
    }

    private static Integer optionalInteger(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) return null;
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("INVALID_" + name.toUpperCase(java.util.Locale.ROOT));
        return value.intValue();
    }

    private static String optionalText(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("INVALID_" + name.toUpperCase(java.util.Locale.ROOT));
        return value.textValue();
    }

    private static GaragePrincipal principal(Authentication authentication) {
        return (GaragePrincipal) authentication.getPrincipal();
    }

    private static long parseId(String value, String code) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(code);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(code, invalid); }
    }

    public record LaborCreateRequest(@NotNull @Min(0) Long expectedVersion,
                                     String serviceId,
                                     @NotBlank @Size(max = 200) String description,
                                     @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,3})?") String quantity,
                                     @NotNull @Min(0) Long unitPrice) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) { throw new IllegalArgumentException("UNKNOWN_FIELD"); }
    }
}
