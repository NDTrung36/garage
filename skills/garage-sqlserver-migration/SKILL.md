---
name: garage-sqlserver-migration
description: Thay đổi hoặc kiểm chứng schema, dữ liệu seed và migration Flyway của Garage trên SQL Server.
---

# Migration SQL Server của Garage

1. Đọc [ERD](../../docs/erd.md), [quy ước Flyway](../../backend/src/main/resources/db/migration/README.md) và các migration liên quan. Kiểm tra version thực tế trong thư mục migration trước khi đặt tên. Xác định rõ đường nâng cấp từ schema đang dùng và cách xử lý hàng đã có.
2. Giữ nguyên migration đã áp dụng. Dùng migration mới cho đổi kiểu, backfill, default, CHECK, unique, FK hoặc index. Kiểm tra kiểu và nullable khớp dữ liệu cũ, hạng mục quản lý bằng Flyway và mapping hiện có. Giữ tiền theo kiểu số chính xác và dùng NVARCHAR cho dữ liệu tiếng Việt.
3. Nếu một lệnh tham chiếu cột vừa thêm, kiểm tra cách SQL Server biên dịch batch. V004 cần `GO` giữa bước thêm cột và bước tạo CHECK trên cột đó. Nếu thay filtered index, kiểm tra cả các tùy chọn SET của kết nối ghi.
4. Cập nhật [FoundationMigrationIT](../../backend/src/test/java/vn/garage/migration/FoundationMigrationIT.java) theo schema đích. Kiểm tra metadata, default và các giá trị bị constraint từ chối. Khi có backfill hoặc thay đổi quy tắc trên dữ liệu cũ, bổ sung fixture tạo ở version trước rồi nâng cấp.
5. Dùng `mvn -Psqlserver-it verify` theo [hướng dẫn backend](../../backend/README.md). Dùng `MIGRATION_TEST_URL` trỏ tới database test riêng và giữ kiểm tra `DB_NAME()` của harness. Test migrate từ database trống, rồi chạy lại để xác nhận 0 migration mới. Nếu đã tạo database tạm cho lượt chạy, dọn đúng database đã tạo.

Chốt kết quả với version đã chạy và các test đã đạt. Nếu lỗi kết nối khiến chưa chạy được SQL Server, ghi rõ lỗi và phần còn chờ kiểm chứng. Build Maven hoặc DB thay thế chỉ cung cấp bằng chứng cho phần mà chúng thực sự kiểm tra.
