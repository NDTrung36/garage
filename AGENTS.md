# Quy tắc làm việc cho agent Garage

Trả lời bằng tiếng Việt, ngắn gọn và dễ hiểu. Đây là MVP cho một garage. Backend dùng Java 21, Spring Boot, SQL Server và Flyway. Frontend dùng React, TypeScript và Vite.

## Nguồn tham chiếu

- Dùng [đặc tả](docs/requirements.md) để xác định hành vi nghiệp vụ, [kiến trúc](docs/architecture.md) để kiểm tra hợp đồng API và thứ tự khóa, và [ERD](docs/erd.md) để hiểu quan hệ dữ liệu. Đối chiếu với code và migration để phân biệt thiết kế dự kiến với phần đã triển khai.
- Khi sửa hành vi đã được chốt, cập nhật những đoạn tài liệu liên quan trong cùng thay đổi. Nếu đặc tả mâu thuẫn và việc chọn một cách hiểu sẽ thay đổi hành vi người dùng, nêu rõ điểm cần quyết định.

## Quy tắc dự án

- Giữ ranh giới module. `identity` quản lý tài khoản nhân viên, `customer` quản lý khách và xe, `catalog` quản lý dịch vụ, `repair` quản lý lịch, booking và phiếu sửa, `billing` quản lý hóa đơn, `inventory` quản lý kho. Qua ranh giới module, gọi hợp đồng lớp `application`. Đặt truy vấn và adapter trong `infrastructure` khi thêm code mới. Giữ `domain` độc lập với Spring, HTTP và JPA.
- Giữ Guest Booking độc lập với đăng nhập nhân viên. `Customer` và `User` là hai mô hình riêng. Chuẩn hóa số điện thoại trước khi tìm khách. Form guest dùng lại hồ sơ phù hợp và lưu snapshot của lần đặt, tránh ghi đè hồ sơ đã có.
- Theo hợp đồng MVP, khách có thể tra cứu bằng số điện thoại hoặc mã đặt lịch mà không đăng nhập. Trả đúng danh sách trường công khai trong đặc tả và che biển số theo quy tắc hiện có. Kết quả tra cứu chỉ cho phép đọc.
- Kiểm tra role tại backend. Yêu cầu CSRF cho lệnh ghi nội bộ dùng session. Khi khóa, mở khóa, đổi role hoặc reset mật khẩu, tăng `users.auth_version` trong cùng transaction. Request nội bộ kế tiếp phải kiểm tra lại hiệu lực phiên từ DB.
- API xem slot chỉ đọc. Đường ghi phải kiểm tra lại sức chứa dưới khóa slot. Khi đối soát lịch, lấy khóa cấu hình trước rồi khóa các slot theo ID tăng dần. Giữ ID và khung giờ của slot có booking hoặc override.
- Dùng Flyway cho thay đổi schema và tạo version mới để sửa schema đã áp dụng. Cập nhật test hợp đồng schema khi thay đổi metadata hoặc ràng buộc. Kiểm chứng hành vi SQL Server trên database test riêng.
- Khi bàn giao, ghi rõ phần đã làm, kiểm tra đã chạy và tiêu chí còn thiếu bằng chứng. Phân biệt kết quả build, unit test, integration test và nghiệm thu qua trình duyệt.

## Skill trong repo

Mở `SKILL.md` phù hợp trước khi bắt đầu phần việc tương ứng.

| Công việc | Skill |
|---|---|
| Thay đổi schema hoặc kiểm thử Flyway | [garage-sqlserver-migration](skills/garage-sqlserver-migration/SKILL.md) |
| Sửa lịch, slot, sức chứa hoặc booking | [garage-schedule-booking](skills/garage-schedule-booking/SKILL.md) |
| Kiểm chứng flow hoặc nghiệm thu feature | [garage-feature-acceptance](skills/garage-feature-acceptance/SKILL.md) |

Dùng `review-agent` khi user yêu cầu review chỉ đọc và skill đó có sẵn.
