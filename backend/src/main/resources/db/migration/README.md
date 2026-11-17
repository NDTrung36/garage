# Migration Flyway — SQL Server

Schema nền cho Guest Booking, tương thích SQL Server 2022+. Một backend, một database và một lịch sử Flyway chung.

## Migration hiện có

| Version | Nội dung |
|---|---|
| V001 | users, customers, vehicles, services |
| V002 | garage_settings, working_hours, calendar_exceptions, appointment_slots, bookings, booking_services, idempotency_records |
| V003 | Một cấu hình garage và 12 khoảng giờ làm Thứ 2–Thứ 7 |

Tổng cộng 11 bảng nền. Chưa tạo bảng sửa chữa, kho, hóa đơn, audit hay tài khoản khách. Không seed user/dịch vụ/khách/xe/booking và không sinh slot theo ngày hiện tại; generated_through để NULL cho scheduler sau này.

## Quy ước

- Đặt tên theo `V001__create_identity_customer_catalog.sql`; version tăng không trùng toàn backend.
- Một ứng dụng, một database, một lịch sử Flyway dùng chung cho sáu module.
- Không chỉnh script version đã chạy; bổ sung migration mới để sửa đổi.
- Dùng NVARCHAR cho tiếng Việt, DATETIMEOFFSET(3) cho thời điểm UTC, DECIMAL cho tiền, BIT cho boolean. Timestamps có default khi INSERT; ứng dụng chịu trách nhiệm cập nhật updated_at.
- Dùng T-SQL tương thích SQL Server 2022+; không dùng `SERIAL`, `TIMESTAMPTZ`, `ON CONFLICT` hoặc `SELECT FOR UPDATE`.
- Không thêm `GO` nếu script không cần phân batch; giữ migration đơn giản và kiểm chứng trên SQL Server.
- Không chứa mật khẩu, tài khoản Admin mặc định hoặc dữ liệu khách thực.
- Không dùng IF NOT EXISTS để bỏ qua schema sai. Constraint và index tạo cùng migration sở hữu bảng; không có trigger hoặc stored procedure nghiệp vụ.
- FK dùng NO ACTION. Filtered unique index chặn cùng biển số/slot ở CONFIRMED, CHECKED_IN, COMPLETED; CANCELLED/NO_SHOW giải phóng quyền đặt lại. Walk-in có slot null và bắt buộc nhân viên tạo.
- Idempotency scope/key dùng collation BIN2 phân biệt hoa/thường; hash SHA-256 lowercase 64 ký tự, JSON hợp lệ, expiry lớn hơn created_at. Các cột khác giữ collation database; ứng dụng chuẩn hóa username/mã/SĐT/biển số.
- CHECK trên SĐT chỉ bảo vệ dạng canonical +84 và 9 chữ số; đầu số di động hợp lệ do application kiểm tra. Biển số chuẩn hóa chỉ chứa A–Z/0–9.
- Capacity theo số booking, khoảng giờ chồng lấn và ít nhất một dịch vụ thuộc application, không dùng trigger.

Database phải được tạo trước. Flyway không tạo instance, login hoặc đổi cấu hình mạng/xác thực SQL Server. clean bị tắt và baseline-on-migrate=false.

Flyway khởi chạy trước JPA khi ứng dụng kết nối hợp lệ. Giữ ddl-auto=none do chưa có entity; chuyển validate khi triển khai mapping, không dùng update/create để thay migration. Hướng dẫn profile mvn -Psqlserver-it verify nằm trong [backend README](../../../../../README.md).

Thiết kế bảng và thứ tự phụ thuộc nằm trong [ERD](../../../../../../docs/erd.md).
