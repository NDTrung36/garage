package vn.garage.billing.api;

import java.util.List;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.billing.application.InvoiceQuery;
import vn.garage.billing.domain.InvoicePayment;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.support.idempotency.IdempotencyExecutor;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = BillingErrors.class)
public class BillingErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalidInput(IllegalArgumentException failure) {
        if (failure instanceof InvoicePayment.InvalidMethod || "INVALID_PAYMENT_METHOD".equals(failure.getMessage())) {
            return error(400, "INVALID_PAYMENT_METHOD", "Phương thức thanh toán không hợp lệ.");
        }
        if ("INVALID_PAYMENT_RESET_REASON".equals(failure.getMessage())) {
            return error(400, "INVALID_PAYMENT_RESET_REASON", "Lý do sửa ghi nhận phải có từ 1 đến 500 ký tự.");
        }
        return malformedRequest();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> malformedRequest() {
        return error(400, "INVALID_INPUT", "Dữ liệu gửi lên không hợp lệ.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorBody> missingKey() {
        return error(400, "IDEMPOTENCY_KEY_REQUIRED", "Thiếu mã yêu cầu.");
    }

    @ExceptionHandler(IdempotencyExecutor.InvalidIdempotencyKeyException.class)
    ResponseEntity<ErrorBody> invalidKey() {
        return error(400, "INVALID_IDEMPOTENCY_KEY", "Mã yêu cầu không hợp lệ.");
    }

    @ExceptionHandler(InvoiceQuery.InvoiceNotFound.class)
    ResponseEntity<ErrorBody> invoiceNotFound() {
        return error(404, "INVOICE_NOT_FOUND", "Không tìm thấy hóa đơn.");
    }

    @ExceptionHandler(InvoicePaymentCommands.StaleVersion.class)
    ResponseEntity<ErrorBody> staleVersion() {
        return error(409, "STALE_VERSION", "Hóa đơn đã thay đổi. Hãy tải lại trước khi thao tác.");
    }

    @ExceptionHandler(InvoicePaymentCommands.AlreadyPaid.class)
    ResponseEntity<ErrorBody> alreadyPaid() {
        return error(409, "INVOICE_ALREADY_PAID", "Hóa đơn đã được ghi nhận thu đủ.");
    }

    @ExceptionHandler(InvoicePaymentCommands.NotPaid.class)
    ResponseEntity<ErrorBody> notPaid() {
        return error(409, "INVOICE_NOT_PAID", "Hóa đơn chưa được ghi nhận thu đủ.");
    }

    @ExceptionHandler(RepairCompletionService.InconsistentRepair.class)
    ResponseEntity<ErrorBody> inconsistentInvoice(RepairCompletionService.InconsistentRepair failure) {
        ErrorBody response = body("INVOICE_DATA_INCONSISTENT", "Dữ liệu hóa đơn không nhất quán.");
        LoggerFactory.getLogger(BillingErrors.class)
                .error("Invoice invariant failed, traceId={}", response.traceId(), failure);
        return ResponseEntity.internalServerError().body(response);
    }

    @ExceptionHandler(IdempotencyExecutor.IdempotencyConflictException.class)
    ResponseEntity<ErrorBody> idempotencyConflict() {
        return error(409, "IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã được dùng cho dữ liệu khác.");
    }

    private static ResponseEntity<ErrorBody> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    @ExceptionHandler(IdempotencyExecutor.RequestInProgressException.class)
    ResponseEntity<ErrorBody> requestInProgress() {
        return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1")
                .body(body("REQUEST_IN_PROGRESS", "Yêu cầu đang được xử lý. Hãy thử lại cùng mã yêu cầu."));
    }

    private static ErrorBody body(String code, String message) {
        return new ErrorBody(code, message, List.of(), UUID.randomUUID().toString());
    }

    record ErrorBody(String code, String message, List<FieldError> fieldErrors, String traceId) { }
    record FieldError(String field, String code) { }
}
