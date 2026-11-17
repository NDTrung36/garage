# Migration nền: phạm vi và bằng chứng kiểm thử

Ngày kiểm chứng: 17/11/2026. Công nghệ: Java 21, Spring Boot 3.5.16, Flyway 11.7.2, Microsoft JDBC 12.10.2 và SQL Server Express 2022 (16.0.1000.6).

## 1. Phạm vi đã triển khai

| Version | Bảng/dữ liệu |
|---|---|
| V001 | users, customers, vehicles, services |
| V002 | garage_settings, working_hours, calendar_exceptions, appointment_slots, bookings, booking_services, idempotency_records |
| V003 | Một cấu hình garage; 12 khoảng giờ làm Thứ 2–Thứ 7 |

Tổng cộng 11 bảng nghiệp vụ, 95 cột và 7 khóa ngoại. Flyway tạo thêm flyway_schema_history để quản lý version/checksum; bảng này không tính vào 11 bảng nghiệp vụ.

Seed không có user, dịch vụ, khách, xe, booking, slot hoặc dữ liệu demo. generated_through là NULL, chờ scheduler ở giai đoạn sau. Chưa triển khai bảng repair/inventory/billing/audit, entity hoặc API.

## 2. Kiểm thử tự động

Profile Maven: `sqlserver-it`. Test: [FoundationMigrationIT](../backend/src/test/java/vn/garage/migration/FoundationMigrationIT.java). Hướng dẫn kết nối và chạy lại: [backend README](../backend/README.md).

Đã chạy Flyway/JDBC trên database mới được tạo riêng, rồi chạy lại toàn bộ profile trên cùng database. Cả hai lần: **12 test, 0 failure, 0 error, 0 skipped**; Maven BUILD SUCCESS.

| Nhóm kiểm tra | Kết quả |
|---|---|
| Flyway migrate/validate | Áp dụng đủ 3 migration; lần sau không có migration cần chạy và không seed lặp |
| Metadata | Đủ bảng, cột, kiểu/độ dài/precision/scale, nullable và identity theo hợp đồng nền |
| Quan hệ/index | 7 FK hợp lệ, không cascade; có filtered unique biển số/slot và index hết hạn idempotency |
| Customer/vehicle | Chặn SĐT/xe trùng trong cùng khách; cho cùng biển số ở khách khác; giữ Unicode và UTC mặc định |
| Booking ownership | Chặn booking trỏ xe không thuộc customer đã chọn |
| Giữ lượt | Cùng biển số/slot bị chặn ở CONFIRMED/CHECKED_IN/COMPLETED, kể cả khác customer; hủy/không đến không giữ quyền unique |
| Walk-in | Cho phép slot null khi có Staff và trạng thái hợp lệ; từ chối sai source/slot/người tạo |
| Dịch vụ yêu cầu | Chặn FK sai, dịch vụ lặp trên booking và snapshot giá âm |
| CHECK | Từ chối enum, giá, cấu hình/giờ hẹn không hợp lệ |
| Idempotency | Unique scope/key, phân biệt key hoa/thường; kiểm tra JSON, hash, HTTP status và expiry |
| Rollback | Booking thất bại và rollback không để lại customer/vehicle mới |
| Cạnh tranh | Request cùng key chờ commit để đọc kết quả; request đầu rollback cho phép request sau xử lý và ghi kết quả |

Profile kiểm tra tên database trước khi chạy Flyway và từ chối target ngoài garage_migration_test hoặc garage_migration_test_<suffix>. Thiếu MIGRATION_TEST_URL đã được kiểm tra: profile thất bại rõ ràng, không tự skip hoặc dùng DB_URL của ứng dụng.

## 3. Môi trường và giới hạn

- Chỉ dùng database nghiệm thu tên ngẫu nhiên trên SQL Server local; database đó đã được xóa sau khi kiểm tra. Không chạy migration trên database nghiệp vụ.
- Đã kiểm tra T-SQL trực tiếp bằng Windows Authentication trước khi chạy Flyway/JDBC; kết quả schema và constraint đạt.
- Theo xác nhận của chủ dự án, TCP của SQLEXPRESS đã bật chỉ tại 127.0.0.1:51433 và dịch vụ đã restart. Windows Authentication được giữ, không mở firewall.
- Thư viện xác thực native nằm trong cache dự án, có chữ ký Microsoft hợp lệ; không cài vào thư mục hệ thống hoặc commit DLL.
- Kiểm thử không khởi động Spring context hoặc tạo SecurityFilterChain. Chưa kiểm chứng API, giao diện, scheduler, phân quyền hoặc luồng sửa chữa/kho/thanh toán.
- Capacity theo số booking, khoảng giờ chồng lấn, dịch vụ phù hợp xe và ít nhất một dịch vụ vẫn cần application service. CHECK/FK không thay thế các quy tắc nhiều dòng này.
- Không dùng Flyway clean hoặc tự động sửa schema trong test. ddl-auto vẫn là none; chỉ chuyển validate khi có entity mapping.

## 4. Tài liệu liên quan

- [ERD và từ điển dữ liệu](erd.md).
- [Kiến trúc, transaction và API dự kiến](architecture.md).
- [Đặc tả nghiệp vụ](requirements.md).
