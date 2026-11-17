package vn.garage.identity.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.garage.identity.application.UserManagementService;

@RestControllerAdvice
public class IdentityExceptionHandler {
    @ExceptionHandler(BadCredentialsException.class) public ResponseEntity<?> badCredentials() { return ResponseEntity.status(401).body(Map.of("code","INVALID_CREDENTIALS","message","Thông tin đăng nhập không hợp lệ.")); }
    @ExceptionHandler(org.springframework.security.authentication.AuthenticationServiceException.class) public ResponseEntity<?> authenticationUnavailable() { return ResponseEntity.status(503).body(Map.of("code","IDENTITY_UNAVAILABLE","message","Dịch vụ xác thực tạm thời không khả dụng.")); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("code",e.getMessage()==null?"INVALID_INPUT":e.getMessage(),"message","Dữ liệu gửi lên không hợp lệ.")); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        String code=e.getMessage()==null?"CONFLICT":e.getMessage(); int status=code.equals("FORBIDDEN")?403:code.equals("USERNAME_EXISTS")||code.startsWith("STALE_")||code.startsWith("LAST_")||code.startsWith("SELF_")?409:400;
        return ResponseEntity.status(status).body(Map.of("code",code,"message","Không thể hoàn tất thao tác."));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) public ResponseEntity<?> duplicate() { return ResponseEntity.status(409).body(Map.of("code","USERNAME_EXISTS","message","Tên đăng nhập đã được sử dụng.")); }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<?> validation() { return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_FAILED","message","Dữ liệu gửi lên không hợp lệ.")); }
    @ExceptionHandler(UserManagementService.UserNotFoundException.class) public ResponseEntity<?> notFound() { return ResponseEntity.status(404).body(Map.of("code","USER_NOT_FOUND","message","Không tìm thấy tài khoản.")); }
}
