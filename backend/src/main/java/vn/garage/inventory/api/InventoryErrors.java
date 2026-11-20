package vn.garage.inventory.api;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.garage.inventory.application.StockCommands;
import vn.garage.inventory.application.PartCatalog;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.repair.api.StaffStockMovementsController;
import vn.garage.support.idempotency.IdempotencyExecutor;

@RestControllerAdvice(assignableTypes = {PartController.class, AdminStockController.class,
        StaffStockMovementsController.class})
public class InventoryErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> inventoryConstraintConflict() {
        return ResponseEntity.status(409).body(Map.of("code", "INVENTORY_CONFLICT", "message", "Dữ liệu kho đã thay đổi hoặc đang được sử dụng."));
    }

    @ExceptionHandler(PartCatalog.NotFound.class)
    public ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("code", "PART_NOT_FOUND", "message", "Không tìm thấy phụ tùng."));
    }

    @ExceptionHandler(PartCatalog.DuplicateCode.class)
    public ResponseEntity<?> duplicateCode() {
        return ResponseEntity.status(409).body(Map.of("code", "PART_CODE_EXISTS", "message", "Mã phụ tùng đã được sử dụng."));
    }

    @ExceptionHandler(PartCatalog.UnitLocked.class)
    public ResponseEntity<?> unitLocked() {
        return ResponseEntity.status(409).body(Map.of("code", "PART_UNIT_LOCKED", "message", "Không thể đổi đơn vị vì phụ tùng đã có biến động kho."));
    }

    @ExceptionHandler(StockCommands.PartNotFound.class)
    public ResponseEntity<?> stockPartNotFound() {
        return ResponseEntity.status(404).body(Map.of("code", "PART_NOT_FOUND", "message", "Không tìm thấy phụ tùng."));
    }

    @ExceptionHandler(StockCommands.StockLimitExceeded.class)
    public ResponseEntity<?> stockLimitExceeded() {
        return ResponseEntity.status(409).body(Map.of("code", "STOCK_LIMIT_EXCEEDED", "message", "Tồn kho vượt giới hạn hoặc không thể âm."));
    }

    @ExceptionHandler(StockQuantity.OutOfRange.class)
    public ResponseEntity<?> invalidQuantityRange() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_STOCK_QUANTITY", "message", "Số lượng không hợp lệ."));
    }

    @ExceptionHandler(IdempotencyExecutor.InvalidIdempotencyKeyException.class)
    public ResponseEntity<?> invalidIdempotencyKey() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_IDEMPOTENCY_KEY", "message", "Mã yêu cầu không hợp lệ."));
    }

    @ExceptionHandler(IdempotencyExecutor.IdempotencyConflictException.class)
    public ResponseEntity<?> idempotencyConflict() {
        return ResponseEntity.status(409).body(Map.of("code", "IDEMPOTENCY_CONFLICT", "message", "Mã yêu cầu đã được dùng cho dữ liệu khác."));
    }

    @ExceptionHandler(IdempotencyExecutor.RequestInProgressException.class)
    public ResponseEntity<?> requestInProgress() {
        return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("code", "REQUEST_IN_PROGRESS", "message", "Yêu cầu đang được xử lý. Hãy thử lại cùng mã yêu cầu."));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> missingIdempotencyHeader() {
        return ResponseEntity.badRequest().body(Map.of("code", "IDEMPOTENCY_KEY_REQUIRED", "message", "Thiếu mã yêu cầu."));
    }
}
