package vn.garage.repair.api;

import java.util.List;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.garage.repair.application.GuestBookingService;
import vn.garage.support.idempotency.IdempotencyExecutor;

@RestControllerAdvice(assignableTypes = PublicBookingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicBookingErrors {
    @ExceptionHandler(GuestBookingService.BookingConflictException.class)
    ResponseEntity<ErrorBody> bookingConflict() { return error(409, "BOOKING_CONFLICT", "Biển số xe đã có lịch trong khung giờ này."); }
    @ExceptionHandler(GuestBookingService.SlotFullException.class)
    ResponseEntity<ErrorBody> slotFull() { return error(409, "SLOT_FULL", "Khung giờ đã hết chỗ. Vui lòng chọn giờ khác."); }
    @ExceptionHandler({GuestBookingService.IdempotencyConflictException.class, IdempotencyExecutor.IdempotencyConflictException.class})
    ResponseEntity<ErrorBody> idempotencyConflict() { return error(409, "IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã được dùng cho nội dung khác."); }
    @ExceptionHandler(IdempotencyExecutor.InvalidIdempotencyKeyException.class)
    ResponseEntity<ErrorBody> invalidIdempotencyKey() { return error(400, "INVALID_IDEMPOTENCY_KEY", "Mã yêu cầu không hợp lệ."); }
    @ExceptionHandler({GuestBookingService.RequestInProgressException.class, IdempotencyExecutor.RequestInProgressException.class})
    ResponseEntity<ErrorBody> requestInProgress() {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("REQUEST_IN_PROGRESS", "Yêu cầu đang được xử lý. Vui lòng thử lại cùng mã yêu cầu."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalid(IllegalArgumentException ex) {
        String code = ex.getMessage() == null ? "INVALID_INPUT" : ex.getMessage();
        int status = code.equals("SLOT_FULL") || code.equals("SLOT_UNAVAILABLE") ? 409 : 400;
        String message = switch (code) {
            case "INVALID_PHONE" -> "Số điện thoại Việt Nam không hợp lệ.";
            case "SERVICE_NOT_AVAILABLE" -> "Dịch vụ không còn khả dụng cho loại xe đã chọn.";
            case "VEHICLE_TYPE_CONFLICT" -> "Biển số này đã được lưu với loại xe khác.";
            case "SLOT_UNAVAILABLE" -> "Khung giờ hiện không khả dụng. Vui lòng chọn giờ khác.";
            case "SLOT_FULL" -> "Khung giờ đã hết chỗ. Vui lòng chọn giờ khác.";
            default -> "Dữ liệu gửi lên không hợp lệ.";
        };
        return error(status, code, message);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        var fields = ex.getBindingResult().getFieldErrors().stream()
                .map(item -> new FieldError(item.getField(), "INVALID_VALUE")).distinct().toList();
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_FAILED", "Dữ liệu gửi lên không hợp lệ.", fields, UUID.randomUUID().toString()));
    }
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorBody> missingHeader() { return error(400, "IDEMPOTENCY_KEY_REQUIRED", "Thiếu mã yêu cầu."); }
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorBody> databaseUnavailable() { return error(503, "SERVICE_UNAVAILABLE", "Dịch vụ đang tạm thời không khả dụng."); }

    private static ResponseEntity<ErrorBody> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }
    private static ErrorBody body(String code, String message) {
        return new ErrorBody(code, message, List.of(), UUID.randomUUID().toString());
    }
    public record FieldError(String field, String code) { }
    public record ErrorBody(String code, String message, List<FieldError> fieldErrors, String traceId) { }
}
