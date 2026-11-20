package vn.garage.repair.api;

import java.util.List;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.repair.application.RepairOrderService;
import vn.garage.repair.application.StaffBookingQuery;
import vn.garage.repair.application.VehicleIntakeService;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@RestControllerAdvice(basePackages = "vn.garage.repair.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RepairErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalidRepairInput(IllegalArgumentException error) {
        return error(400, error.getMessage() == null ? "INVALID_INPUT" : error.getMessage(), "Dữ liệu gửi lên không hợp lệ.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorBody> missingRequestHeader() {
        return error(400, "IDEMPOTENCY_KEY_REQUIRED", "Thiếu mã yêu cầu.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidRepairRequest() {
        return error(400, "VALIDATION_FAILED", "Dữ liệu gửi lên không hợp lệ.");
    }

    @ExceptionHandler({VehicleIntakeService.InvalidIntakeInput.class, StaffBookingQuery.InvalidBookingQuery.class,
            StaffBookingsController.InvalidBookingId.class})
    ResponseEntity<ErrorBody> invalidIntake() { return error(400, "INVALID_INPUT", "Dữ liệu gửi lên không hợp lệ."); }

    @ExceptionHandler(RepairOrderService.InvalidLaborInput.class)
    ResponseEntity<ErrorBody> invalidLaborInput() { return error(400, "INVALID_REPAIR_INPUT", "Dữ liệu phiếu sửa chữa không hợp lệ."); }

    @ExceptionHandler(RepairOrderService.RepairOrderNotFound.class)
    ResponseEntity<ErrorBody> repairOrderNotFound() { return error(404, "REPAIR_ORDER_NOT_FOUND", "Không tìm thấy phiếu sửa chữa."); }

    @ExceptionHandler(RepairOrderService.LaborRepositoryNotFound.class)
    ResponseEntity<ErrorBody> laborItemNotFound() { return error(404, "LABOR_ITEM_NOT_FOUND", "Không tìm thấy dòng công việc trên phiếu."); }

    @ExceptionHandler(RepairOrderService.RepairNotInProgress.class)
    ResponseEntity<ErrorBody> repairOrderNotInProgress() { return error(409, "REPAIR_NOT_IN_PROGRESS", "Phiếu không còn đang sửa."); }

    @ExceptionHandler(RepairOrderService.StaleVersion.class)
    ResponseEntity<ErrorBody> repairOrderStaleVersion() { return error(409, "STALE_VERSION", "Phiếu đã thay đổi. Hãy tải lại trước khi sửa."); }

    @ExceptionHandler(RepairOrderService.LaborItemVoided.class)
    ResponseEntity<ErrorBody> laborItemVoided() { return error(409, "LABOR_ITEM_VOIDED", "Dòng công việc đã được bỏ khỏi phiếu."); }

    @ExceptionHandler(VehicleIntakeService.BookingNotFound.class)
    ResponseEntity<ErrorBody> bookingNotFound() { return error(404, "BOOKING_NOT_FOUND", "Không tìm thấy lịch hẹn."); }

    @ExceptionHandler(VehicleIntakeService.BookingStateConflict.class)
    ResponseEntity<ErrorBody> bookingStateConflict() { return error(409, "BOOKING_STATE_CONFLICT", "Lịch hẹn không ở trạng thái có thể tiếp nhận."); }

    @ExceptionHandler(VehicleIntakeService.StaleBookingVersion.class)
    ResponseEntity<ErrorBody> staleBookingVersion() { return error(409, "STALE_VERSION", "Lịch hẹn đã thay đổi. Hãy tải lại trước khi tiếp nhận."); }

    @ExceptionHandler(VehicleIntakeService.RepairInvariantViolation.class)
    ResponseEntity<ErrorBody> repairInvariantViolation() { return error(500, "REPAIR_DATA_INCONSISTENT", "Dữ liệu phiếu sửa chữa không nhất quán."); }

    @ExceptionHandler(RepairCompletionService.InconsistentRepair.class)
    ResponseEntity<ErrorBody> inconsistentCompletion(RepairCompletionService.InconsistentRepair failure) {
        var response = body("REPAIR_DATA_INCONSISTENT", "Dữ liệu phiếu sửa chữa không nhất quán.");
        org.slf4j.LoggerFactory.getLogger(RepairErrors.class)
                .error("Repair completion invariant failed, traceId={}", response.traceId(), failure);
        return ResponseEntity.internalServerError().body(response);
    }

    @ExceptionHandler(RepairCompletionService.InvoiceCodeRetriesExhausted.class)
    ResponseEntity<ErrorBody> invoiceCodeUnavailable() {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("INVOICE_CODE_UNAVAILABLE", "Chưa tạo được mã hóa đơn. Vui lòng thử lại."));
    }

    @ExceptionHandler(VehicleIntakeService.BookingCodeRetriesExhausted.class)
    ResponseEntity<ErrorBody> bookingCodeRetriesExhausted() {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("BOOKING_CODE_UNAVAILABLE", "Chưa tạo được mã tiếp nhận. Vui lòng thử lại cùng mã yêu cầu."));
    }

    @ExceptionHandler({SqlServerWriteTransactions.LockTimeoutException.class,
            SqlServerWriteTransactions.DeadlockRetriesExhaustedException.class})
    ResponseEntity<ErrorBody> writeLockUnavailable() {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("WRITE_TEMPORARILY_UNAVAILABLE", "Thao tác đang bận. Vui lòng thử lại."));
    }

    @ExceptionHandler({RepairPartCommands.RepairOrderNotFound.class, RepairPartRepository.RepairOrderNotFound.class})
    ResponseEntity<ErrorBody> missingRepairOrder() { return error(404, "REPAIR_ORDER_NOT_FOUND", "Không tìm thấy phiếu sửa chữa."); }

    @ExceptionHandler({RepairPartCommands.PartItemNotFound.class, RepairPartRepository.PartItemNotFound.class})
    ResponseEntity<ErrorBody> missingPartItem() { return error(404, "PART_ITEM_NOT_FOUND", "Không tìm thấy dòng phụ tùng."); }

    @ExceptionHandler(RepairStockOperations.PartNotFound.class)
    ResponseEntity<ErrorBody> missingPart() { return error(404, "PART_NOT_FOUND", "Không tìm thấy phụ tùng."); }

    @ExceptionHandler(RepairStockOperations.IssueNotFound.class)
    ResponseEntity<ErrorBody> missingIssue() { return error(404, "ISSUE_NOT_FOUND", "Không tìm thấy lần xuất thuộc phiếu."); }

    @ExceptionHandler(RepairPartCommands.RepairNotInProgress.class)
    ResponseEntity<ErrorBody> repairNotInProgress() { return error(409, "REPAIR_NOT_IN_PROGRESS", "Phiếu không còn đang sửa."); }

    @ExceptionHandler(RepairPartCommands.StaleVersion.class)
    ResponseEntity<ErrorBody> staleVersion() { return error(409, "STALE_VERSION", "Phiếu đã thay đổi. Hãy tải lại trước khi sửa."); }

    @ExceptionHandler(RepairStockOperations.PartInactive.class)
    ResponseEntity<ErrorBody> inactivePart() { return error(409, "PART_INACTIVE", "Phụ tùng đã ngừng hoạt động."); }

    @ExceptionHandler(RepairStockOperations.PartVehicleMismatch.class)
    ResponseEntity<ErrorBody> vehicleMismatch() { return error(409, "PART_VEHICLE_MISMATCH", "Phụ tùng không phù hợp với loại xe."); }

    @ExceptionHandler(RepairStockOperations.InsufficientStock.class)
    ResponseEntity<ErrorBody> insufficientStock() { return error(409, "INSUFFICIENT_STOCK", "Tồn kho không đủ."); }

    @ExceptionHandler(RepairStockOperations.ReturnExceedsIssue.class)
    ResponseEntity<ErrorBody> returnExceedsIssue() { return error(409, "RETURN_EXCEEDS_ISSUE", "Số lượng hoàn vượt lượng còn được hoàn."); }

    @ExceptionHandler(RepairStockOperations.StockLimitExceeded.class)
    ResponseEntity<ErrorBody> stockLimitExceeded() { return error(409, "STOCK_LIMIT_EXCEEDED", "Số lượng vượt giới hạn."); }

    @ExceptionHandler(RepairTotals.TotalLimitExceeded.class)
    ResponseEntity<ErrorBody> repairTotalLimitExceeded() { return error(409, "REPAIR_TOTAL_LIMIT_EXCEEDED", "Tổng tiền vượt giới hạn."); }

    @ExceptionHandler(IdempotencyExecutor.InvalidIdempotencyKeyException.class)
    ResponseEntity<ErrorBody> invalidIdempotencyKey() {
        return error(400, "INVALID_IDEMPOTENCY_KEY", "Mã yêu cầu không hợp lệ.");
    }

    @ExceptionHandler(IdempotencyExecutor.IdempotencyConflictException.class)
    ResponseEntity<ErrorBody> idempotencyConflict() {
        return error(409, "IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã được dùng cho nội dung khác.");
    }

    @ExceptionHandler(IdempotencyExecutor.RequestInProgressException.class)
    ResponseEntity<ErrorBody> requestInProgress() {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("REQUEST_IN_PROGRESS", "Yêu cầu đang được xử lý. Vui lòng thử lại cùng mã yêu cầu."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> repairConstraintConflict() {
        return error(409, "REPAIR_CONFLICT", "Dữ liệu đã thay đổi hoặc đang được sử dụng.");
    }

    private static ResponseEntity<ErrorBody> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    private static ErrorBody body(String code, String message) {
        return new ErrorBody(code, message, List.of(), UUID.randomUUID().toString());
    }

    public record ErrorBody(String code, String message, List<FieldError> fieldErrors, String traceId) { }
    public record FieldError(String field, String code) { }
}
