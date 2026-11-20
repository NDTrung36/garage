package vn.garage.repair.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.repair.application.StockHistoryQuery;

@RestController
public class StaffStockMovementsController {
    private final StockHistoryQuery history;

    public StaffStockMovementsController(StockHistoryQuery history) { this.history = history; }

    @GetMapping("/api/staff/stock-movements")
    public ResponseEntity<StockHistory.Page> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String partId,
            @RequestParam(required = false) String movementType) {
        Long parsedPartId = partId == null ? null : parsePartId(partId);
        StockMovement.Type type = parseType(movementType);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.list(page, size, parsedPartId, type));
    }

    private static StockMovement.Type parseType(String value) {
        if (value == null) return null;
        try {
            return StockMovement.Type.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INVALID_MOVEMENT_TYPE", invalid);
        }
    }

    private static long parsePartId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("INVALID_PART_ID");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("INVALID_PART_ID", invalid);
        }
    }
}
