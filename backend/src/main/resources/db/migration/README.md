# Migration Flyway — SQL Server

Schema nền cho Guest Booking, tương thích SQL Server 2022+. Một backend, một database và một lịch sử Flyway chung.

## Migration hiện có

| Version | Nội dung |
|---|---|
| V001 | users, customers, vehicles, services |
| V002 | garage_settings, working_hours, calendar_exceptions, appointment_slots, bookings, booking_services, idempotency_records |
| V003 | Một cấu hình garage và 12 khoảng giờ làm Thứ 2–Thứ 7 |
| V004 | Version dịch vụ và override slot |
| V005 | Danh mục `parts`, tồn ban đầu bằng 0 và version |
| V006 | `repair_orders` và `repair_items` (SC-02), gồm cặp `voided_at`/`voided_by` chỉ cho LABOR |
| V007 | Ledger `stock_movements` (feature kho), phụ thuộc V005 và V006 |
| V008 | `audit_logs` (SC-02), có CHECK JSON và index theo entity/actor |
| V009 | `invoices` (SC-02), unique theo mã/phiếu và CHECK trạng thái thanh toán |

Guest booking và lookup dùng lại V001–V002, không thêm migration riêng: customer/vehicle upsert theo unique index, booking lưu snapshot, `booking_services` lưu dịch vụ/giá, `idempotency_records` lưu kết quả 24 giờ.

V001–V003 tạo 11 bảng nền. V005 thêm bảng `parts`, chưa seed phụ tùng. V006 tạo phiếu rỗng, không backfill booking `CONFIRMED`; V007 tạo ledger sau khi V006 đã tạo `repair_orders`/`repair_items`. V008–V009 hoàn tất audit và hóa đơn. `idempotency_records` đã có từ V002 và không được tạo lại.

Schema tích hợp V001–V009 có 17 bảng nghiệp vụ/nền và 21 FK, chưa tính `flyway_schema_history`. INV-10 đã quan sát Flyway áp dụng 9 migration trên DB sạch, chạy lại 0 migration và nâng từ V004 lên V009. Bộ integration test còn ca đỏ, xem [bằng chứng INV-10](../../../../../../docs/plans/phu-tung-ton-kho-handoff.md#bằng-chứng-inv-10).

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

Tạo trước hai database test riêng: `garage_migration_test` và `garage_migration_upgrade_test`. Flyway không tạo instance, database, login hoặc đổi cấu hình mạng/xác thực SQL Server. clean bị tắt và baseline-on-migrate=false.

Flyway khởi chạy trước JPA khi ứng dụng kết nối hợp lệ. Giữ ddl-auto=none do chưa có entity; chuyển validate khi triển khai mapping, không dùng update/create để thay migration. Hướng dẫn profile mvn -Psqlserver-it verify nằm trong [backend README](../../../../../README.md).

Thiết kế bảng và thứ tự phụ thuộc nằm trong [ERD](../../../../../../docs/erd.md).
