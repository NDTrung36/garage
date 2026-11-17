# API client

Thư mục chứa cấu hình HTTP, kiểu lỗi chung và xử lý session/CSRF. `config.ts` khai báo prefix `/api`; chưa có request nghiệp vụ.

- Dùng URL cùng origin; Vite proxy `/api` đến backend khi phát triển.
- Không lưu session token hoặc mật khẩu trong `localStorage`.
- API quản trị dùng cookie session; thao tác thay đổi dữ liệu cần CSRF token khi backend được triển khai.
- DTO public và DTO quản trị phải tách riêng. Không che dữ liệu nhạy cảm chỉ bằng CSS hoặc xử lý frontend.
- Hàm gọi API theo tính năng đặt trong `features/<feature>/api.ts`; không gom nghiệp vụ vào HTTP client chung.
- Chuẩn lỗi và danh sách endpoint dự kiến nằm trong [kiến trúc](../../../docs/architecture.md).
