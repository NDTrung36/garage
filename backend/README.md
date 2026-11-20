# Backend

## Identity MVP

Bootstrap Admin lần đầu bằng cách bật `BOOTSTRAP_ADMIN_ENABLED=true` và cung cấp `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_DISPLAY_NAME`, `BOOTSTRAP_ADMIN_PASSWORD` qua environment. Bootstrap chỉ tạo user khi bảng `users` rỗng; tắt biến sau lần khởi tạo. Mật khẩu dài tối thiểu 12 ký tự, không quá 72 byte UTF-8. Không ghi credentials vào file được commit.

Cookie session hết hạn sau 30 phút không hoạt động. Khi triển khai HTTPS, đặt `SESSION_COOKIE_SECURE=true`. Frontend và API cần cùng origin qua reverse proxy. Test/local có thể để false khi dùng HTTP.

API auth: `GET /api/auth/csrf`, `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`. Admin quản lý user tại `/api/admin/users`. Mọi POST/PATCH nội bộ cần header `X-CSRF-TOKEN` lấy từ endpoint csrf. Xem [plan identity](../docs/identity-plan.md) để biết contract đầy đủ.

Java 21, Maven, Spring Boot 3.5.16, SQL Server và Flyway. Một Maven project; sáu module là package nghiệp vụ, chưa tách thành sáu Maven artifact.

## Module

| Module | Trách nhiệm |
|---|---|
| `identity` | Tài khoản Admin/Staff, đăng nhập, session, phân quyền |
| `customer` | Hồ sơ khách hàng và phương tiện; không yêu cầu khách có tài khoản |
| `catalog` | Danh mục dịch vụ, loại xe áp dụng và giá tham khảo |
| `repair` | Slot, cấu hình lịch, booking, tiếp nhận và phiếu sửa |
| `billing` | Hóa đơn nội bộ, tổng tiền chốt và trạng thái thanh toán |
| `inventory` | Danh mục phụ tùng, nhập kho, xuất dùng, hoàn kho và điều chỉnh |

Mỗi module có bốn lớp:

- `api`: REST controller, request/response DTO, validation đầu vào.
- `application`: use case, transaction, điều phối domain và gọi interface module khác.
- `domain`: mô hình và quy tắc nghiệp vụ, repository port; không phụ thuộc Spring/JPA/HTTP.
- `infrastructure`: JPA entity, repository adapter, cấu hình và tích hợp kỹ thuật.

Module khác chỉ gọi interface thuộc `application`, không dùng trực tiếp repository hoặc JPA entity của nhau. Chưa tạo entity/controller nghiệp vụ trong khung hiện tại.

## Cấu hình và chạy local

Yêu cầu JDK 21, Maven 3.9+, SQL Server 2022+ đang hoạt động. Tạo database `garage` riêng trước khi chạy. Flyway quản lý schema bên trong database, không tạo SQL Server instance hoặc database.

Ví dụ PowerShell, chạy từ thư mục `backend`:

```powershell
$env:DB_URL = 'jdbc:sqlserver://localhost:1433;databaseName=garage;encrypt=true;trustServerCertificate=true'
$env:DB_USERNAME = 'garage_app'
$env:DB_PASSWORD = Read-Host 'Mat khau SQL Server'
mvn spring-boot:run
```

`trustServerCertificate=true` chỉ dùng local với chứng thư tự ký. Production dùng chứng thư hợp lệ và giữ `trustServerCertificate=false` như cấu hình mặc định. Không commit mật khẩu; `.env.example` chỉ mô tả biến, Spring Boot không tự đọc file này.

Tài khoản chạy migration cần quyền tạo/sửa schema. Khi deploy thực tế nên tách tài khoản migration và tài khoản ứng dụng; tài khoản ứng dụng không cần quyền quản trị server.

## Build

```powershell
mvn -DskipTests package
```

Compile/package không cần database. Integration test migration chạy riêng trên SQL Server bằng profile sqlserver-it, không dùng H2.

## Migration

Migration nằm trong `src/main/resources/db/migration`; xem [quy ước Flyway](src/main/resources/db/migration/README.md).

Migrations V001–V003 tạo 11 bảng nền, V004 thêm version dịch vụ và override lịch, V005 tạo danh mục phụ tùng rỗng, V006 tạo phiếu và chi tiết, V007 tạo ledger phụ tùng, V008 tạo audit, V009 tạo hóa đơn. V006 không backfill booking `CONFIRMED`. Kiểm tra [README migration](src/main/resources/db/migration/README.md) và Flyway history thực tế trước khi triển khai version tiếp theo. `application.yml` hiện dùng `ddl-auto=validate`; Flyway vẫn là nguồn thay đổi schema.

Public guest booking/lookup dùng JDBC trên schema nền hiện tại; Customer và Vehicle được tìm/tạo trong transaction, khách không có tài khoản. Rate limit public mặc định dùng `getRemoteAddr()`. Khi đặt sau reverse proxy, `PUBLIC_TRUSTED_PROXIES` phải liệt kê chính xác IP proxy; proxy phải ghi đè header `X-Real-IP` bằng địa chỉ client và loại bỏ header do client gửi. Không tin header từ proxy chưa cấu hình. Mỗi backend instance giữ tối đa 4096 bucket.

## Kết nối ghi, idempotency và audit

Hikari thiết lập các ANSI/ARITHABORT option theo V002 và `LOCK_TIMEOUT 5000` khi mở connection. `SqlServerWriteTransactions` chạy lệnh ghi trong transaction ngoài cùng, tạm đặt timeout 5 giây và khôi phục giá trị session trước khi trả connection cho pool; SQL Server 1205 retry tối đa hai lần sau rollback. `IdempotencyExecutor` khóa composite `(scope, request_key)` trong cùng transaction với nghiệp vụ, phân biệt key hoa/thường, lưu status/response thành công ít nhất 24 giờ và ánh xạ timeout sang `503 Retry-After: 1`. Dọn key hết hạn theo batch qua expiry index. `AuditWriter` ghi actor, reason và snapshot trong transaction do caller mở; API lấy actor từ principal.

Unit test chạy bằng `mvn test`. SQL Server integration cần database riêng `garage_migration_test` qua `MIGRATION_TEST_URL`; test kiểm session settings, filtered unique index trên connection pool ghi, khóa/replay, rollback audit/version, expiry và cleanup. Không chạy nếu chưa cấu hình database test chuyên dụng.

## Integration test migration

Profile dùng Flyway/JDBC thực, kiểm tra metadata, defaults, FK, unique/filter, CHECK, index, seed và chạy lại migration để xác nhận không có thay đổi mới. Cần hai database SQL Server test riêng: `MIGRATION_TEST_URL` cho migrate sạch/rerun và `MIGRATION_TEST_V004_URL` cho nâng cấp V004 với fixture booking `CONFIRMED`; harness kiểm tra `DB_NAME()` riêng cho từng database. Hướng dẫn đầy đủ nằm trong [README migration](src/main/resources/db/migration/README.md).

Chuẩn bị hai database SQL Server test riêng: `garage_migration_test` hoặc `garage_migration_test_<suffix>` để chạy sạch/rerun, và `garage_migration_upgrade_test` hoặc `garage_migration_upgrade_test_<suffix>` để nâng từ V004. Test kiểm tra tên thực bằng DB_NAME() trước khi chạy Flyway; từ chối database khác hoặc có schema lạ. Test không tạo/xóa database, không chạy Flyway clean. Fixture booking upgrade được giữ lại để lần chạy sau xác minh lịch sử nâng cấp.

```sql
CREATE DATABASE garage_migration_test;
CREATE DATABASE garage_migration_upgrade_test;
```

Ví dụ chạy từ backend bằng tài khoản có quyền DDL/CRUD trong database test:

```powershell
$env:MIGRATION_TEST_URL = 'jdbc:sqlserver://localhost:1433;databaseName=garage_migration_test;encrypt=true;trustServerCertificate=true'
$env:MIGRATION_TEST_V004_URL = 'jdbc:sqlserver://localhost:1433;databaseName=garage_migration_upgrade_test;encrypt=true;trustServerCertificate=true'
$env:MIGRATION_TEST_USERNAME = 'garage_test_migrator'
$env:MIGRATION_TEST_PASSWORD = Read-Host 'Mat khau tai khoan test'
mvn -Psqlserver-it verify
```

Thiếu URL hoặc kết nối sai phải làm profile thất bại, không skip test. Không dùng biến DB_URL của ứng dụng để tránh chạy nhầm database. Không chạy hai bộ test cùng lúc vào một database vì fixture được thiết kế tuần tự.

Microsoft JDBC cần SQL Server mở TCP. Nếu instance chỉ cho Windows Authentication, dùng JDBC integrated authentication với thư viện native phù hợp driver và JDK 64-bit; URL thêm integratedSecurity=true, không đặt username/password test. Cấu hình TCP hoặc đổi cơ chế xác thực có thể cần restart instance; đây là việc thiết lập môi trường, không được thực hiện bởi migration/test.

trustServerCertificate=true trong ví dụ chỉ dành local. Test thiết lập các ANSI/ARITHABORT option cần thiết cho filtered index; khi triển khai repository phải bảo đảm các kết nối ghi dữ liệu cũng đáp ứng các option đó.

### Windows Authentication trên máy local hiện tại

Đã kiểm chứng instance SQLEXPRESS chỉ lắng nghe TCP tại 127.0.0.1:51433, giữ Windows Authentication và không mở firewall. Dùng hostname localhost trong JDBC; kết nối qua địa chỉ IP trực tiếp đã gặp lỗi SSPI trên máy này.

Native DLL mssql-jdbc_auth-12.10.2.x64.dll đã tải vào cache dự án và kiểm tra chữ ký Microsoft. Cache không được commit; khi thiết lập máy khác cần cung cấp lại DLL đúng phiên bản driver. Xem [hướng dẫn Microsoft](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url).

Sau khi tạo hai database test, chạy từ backend, trong shell chưa đặt MIGRATION_TEST_USERNAME/PASSWORD:

```powershell
$env:PATH = (Resolve-Path '../.cache/migration/native').Path + ';' + $env:PATH
$env:MIGRATION_TEST_URL = 'jdbc:sqlserver://localhost:51433;databaseName=garage_migration_test;integratedSecurity=true;encrypt=true;trustServerCertificate=true'
$env:MIGRATION_TEST_V004_URL = 'jdbc:sqlserver://localhost:51433;databaseName=garage_migration_upgrade_test;integratedSecurity=true;encrypt=true;trustServerCertificate=true;sendStringParametersAsUnicode=false'
mvn -Psqlserver-it verify
```

Bộ schema tích hợp V001–V009 có 17 bảng nghiệp vụ/nền và 21 FK, chưa tính bảng lịch sử Flyway. INV-10 đã chạy trên cả hai database test và quan sát migrate sạch, chạy lại 0 migration, nâng V004 lên V009. Bộ test vẫn còn lỗi, xem [bằng chứng INV-10](../docs/plans/phu-tung-ton-kho-handoff.md#bằng-chứng-inv-10). Kết quả schema nền trước SC-02 nằm tại [bằng chứng migration](../docs/migration-foundation.md).
