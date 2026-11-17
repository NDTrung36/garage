# Frontend

React + TypeScript + Vite. Hiện có trang khởi tạo tối thiểu, cấu hình TypeScript strict và dev proxy; chưa có form đặt lịch hoặc màn hình quản trị.

## Chạy và build

Yêu cầu Node.js 22.12+; khuyến nghị dùng Node.js 24 cho môi trường phát triển.

```powershell
npm install
npm run dev
npm run build
```

Dev server ở `http://localhost:5173`, backend dự kiến ở `http://localhost:8080`. Dùng `npm ci` sau khi repository đã có `package-lock.json`.

## Tổ chức source

- `src/app`: gắn router, layout và provider khi triển khai.
- `src/api`: cấu hình HTTP/session/CSRF và xử lý lỗi chung.
- `src/features/auth`: đăng nhập Admin/Staff.
- `src/features/booking`: form đặt lịch, lịch hẹn và tiếp nhận.
- `src/features/lookup`: tra cứu công khai.
- `src/features/customer`, `vehicle`: hồ sơ khách và xe.
- `src/features/service`: danh mục dịch vụ.
- `src/features/repair`: công việc thực tế và phiếu sửa.
- `src/features/part`, `inventory`: danh mục phụ tùng và nhập–xuất–tồn.
- `src/features/invoice`: hóa đơn nội bộ và trạng thái thanh toán.
- `src/features/settings`: lịch hoạt động và quản trị tài khoản.

Các feature hiện được giữ bằng `.gitkeep`; chưa tạo component/service rỗng. Khi có chức năng, mỗi feature đặt component, API và type liên quan cạnh nhau. Không cần áp dụng bốn lớp backend cho frontend.
