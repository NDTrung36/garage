---
name: garage-feature-acceptance
description: Kiểm chứng tiêu chí nghiệm thu Garage qua test, SQL Server và trình duyệt, kể cả sau khi sửa findings.
---

# Nghiệm thu feature Garage

1. Lấy tiêu chí từ yêu cầu user, [đặc tả](../../docs/requirements.md) và plan của feature. Gắn mỗi tiêu chí với kịch bản quan sát được. Khi xác nhận bản sửa review, tái hiện điều kiện gây lỗi và chọn test hồi quy đúng lớp.
2. Ưu tiên bài kiểm tra nhỏ nhất đủ chứng minh hành vi. Unit test kiểm tra quy tắc nghiệp vụ và giá trị biên. API test kiểm tra dữ liệu trả về, mã lỗi và quyền. Với phân quyền hoặc CSRF, chạy qua SecurityFilterChain thực. MockMvc standalone có thể kiểm tra DTO và lỗi controller nhưng không xác nhận cấu hình bảo mật.
3. Với schema, truy vấn T-SQL hoặc khóa, dùng SQL Server test riêng theo [hướng dẫn backend](../../backend/README.md). Nếu thay đổi transaction, kiểm tra commit và rollback qua đường gọi thực. Khi nghiệm thu race condition, dùng các kết nối hoặc transaction độc lập.
4. Với UI, chạy `npm run build` trong `frontend/` để kiểm tra TypeScript và đóng gói. Khi tiêu chí có luồng trình duyệt, chạy UI với backend test và dữ liệu tổng hợp. Mock API hữu ích cho trạng thái giao diện, còn nghiệm thu xuyên hệ thống cần kết nối backend và DB test thật.
5. Khi báo kết quả, ghi lệnh đã chạy, kịch bản đạt và lỗi còn tái hiện. Với tiêu chí chưa chạy được, ghi môi trường chặn và bằng chứng còn thiếu. Chốt hoàn thành khi các tiêu chí bắt buộc có kết quả đạt.

Với review chỉ đọc, áp dụng phạm vi user yêu cầu. Chỉ sửa code hoặc thêm test khi task cho phép.
