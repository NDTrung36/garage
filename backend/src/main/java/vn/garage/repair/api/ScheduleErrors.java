package vn.garage.repair.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.garage.repair.application.SlotGenerator.ScheduleException;

@RestControllerAdvice(assignableTypes = {PublicSlotsController.class, AdminScheduleController.class})
public class ScheduleErrors {
    @ExceptionHandler(ScheduleException.class)
    public ResponseEntity<Map<String, String>> scheduleError(ScheduleException error) {
        var response = ResponseEntity.status(error.status());
        if (error.status() == 503) response.header("Retry-After", "60");
        String message = switch (error.getMessage()) {
            case "SLOT_SCHEDULE_UNAVAILABLE" -> "Lịch chưa sẵn sàng. Vui lòng thử lại sau.";
            case "STALE_VERSION" -> "Dữ liệu đã thay đổi. Vui lòng tải lại.";
            default -> "Không thể cập nhật lịch.";
        };
        return response.body(Map.of("code", error.getMessage(), "message", message));
    }
}
