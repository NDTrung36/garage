---
name: garage-schedule-booking
description: Triển khai hoặc sửa giờ làm, ngày nghỉ, slot, override, sức chứa và đặt lịch của Garage.
---

# Lịch garage và booking

Đọc [quy tắc slot trong đặc tả](../../docs/requirements.md#33-quy-tắc-slot) và [luồng transaction trong kiến trúc](../../docs/architecture.md#3-luồng-chính-và-transaction). Đối chiếu logic hiện tại của [SlotPlan](../../backend/src/main/java/vn/garage/repair/domain/SlotPlan.java) và [SlotGenerator](../../backend/src/main/java/vn/garage/repair/application/SlotGenerator.java) trước khi thay đổi lịch.

- Dùng `Clock` và `Asia/Ho_Chi_Minh` để tính ngày, lead time và ngày cuối được đặt. Sinh theo số khoảng hoàn chỉnh trong ca hoặc mốc thời gian có ngày để tránh vòng qua nửa đêm. Dùng số ngày đặt trước từ cấu hình cho startup, scheduler và sinh bù.
- Đối soát khi cấu hình hoặc ngày nghỉ thay đổi. Giữ slot đã bắt đầu và giữ ID, khung giờ của slot có booking bất kỳ trạng thái hoặc có override. Tạo slot mới cùng loại xe khi khoảng thời gian không giao slot được giữ. Tính riêng đóng theo lịch và đóng chủ động của Admin. Cập nhật `generated_through` trong transaction đối soát, kể cả ngày nghỉ không sinh hàng.
- API công khai chỉ trả slot mở, còn chỗ và đủ lead time. Ngày hợp lệ chưa sinh trả `503 SLOT_SCHEDULE_UNAVAILABLE`. Ngày đã sinh nhưng không còn slot phù hợp trả danh sách rỗng. `CONFIRMED`, `CHECKED_IN`, `COMPLETED` chiếm chỗ. `CANCELLED`, `NO_SHOW` chỉ còn là lịch sử. Walk-in không có slot online.
- Khi sửa lịch, khóa settings trước rồi slot theo ID tăng dần. Khi tạo booking, theo thứ tự idempotency, slot rồi hồ sơ khách và xe. Khi đổi nhiều slot, khóa theo ID tăng dần trước khi khóa booking. Đọc lại trạng thái và kiểm tra sức chứa dưới khóa. Áp dụng cùng thứ tự cho mọi đường ghi liên quan.
- Với endpoint yêu cầu `Idempotency-Key`, giữ key qua retry của cùng thao tác. Cùng key và cùng payload trả kết quả cũ. Cùng key nhưng khác payload trả conflict. Lưu dữ liệu nghiệp vụ và kết quả idempotency trong cùng transaction.

Chọn test theo phần thay đổi. Dùng unit test có Clock cố định cho ca tối, biên lead time, ngày cuối, đổi mẫu giờ và override. Dùng SQL Server test riêng cho commit/rollback, sinh bù lặp lại, slot có booking và thao tác cạnh tranh. Nếu chỉ sửa GET, kiểm tra đường đọc không ghi và phân biệt ngày nghỉ với ngày chưa sinh. Khi sửa booking, kiểm tra tranh chỗ, trùng biển số và retry cùng key.
