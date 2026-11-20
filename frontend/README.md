# Frontend

React + TypeScript + Vite. Có đặt lịch công khai, tiếp nhận, phiếu sửa chữa, kho và hóa đơn nội bộ.

## Chạy và build

Yêu cầu Node.js 22.12+; khuyến nghị dùng Node.js 24 cho môi trường phát triển.

```powershell
npm install
npm run dev
npm run build
```

Dev server ở `http://localhost:5173`, backend dự kiến ở `http://localhost:8080`. Dùng `npm ci` sau khi repository đã có `package-lock.json`.

## Nghiệm thu tiếp nhận và sửa chữa

1. Chuẩn bị một database SQL Server trống cho browser và một database nâng cấp V004 theo [Backend README](../backend/README.md). Trỏ `MIGRATION_TEST_URL` vào database browser và chạy từ `backend/`:

   ```powershell
   mvn -Psqlserver-it '-Dit.test=FoundationMigrationIT#migrationsAreValidatedAndNotReapplied' verify
   ```

   Test kiểm tra tên DB thực, seed, lịch sử V001–V009, migrate lại và nâng cấp V004. Dùng một database khác cho lần chạy toàn bộ SQL Server IT.
2. Trỏ `DB_URL` của backend đúng vào database browser vừa kiểm tra. Cấu hình credentials SQL test, bật bootstrap và đặt tên Admin bắt đầu bằng `sc14_`. Khởi động backend trên loopback và port test riêng:

   ```powershell
   $env:PORT = '18086'
   $env:BOOTSTRAP_ADMIN_ENABLED = 'true'
   $env:BOOTSTRAP_ADMIN_USERNAME = 'sc14_admin'
   $env:BOOTSTRAP_ADMIN_DISPLAY_NAME = 'SC14 Admin'
   $env:BOOTSTRAP_ADMIN_PASSWORD = Read-Host 'Mat khau Admin test'
   mvn spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
   ```

3. Từ `frontend/` trong terminal khác, cài Chromium và chạy test. Playwright mở Vite ở `127.0.0.1:5184`; proxy chỉ trỏ tới port backend test đã chọn.

   ```powershell
   npm ci
   npx playwright install chromium
   npm run build
   $env:E2E_BACKEND_PORT = '18086'
   $env:E2E_ADMIN_USERNAME = 'sc14_admin'
   $env:E2E_ADMIN_PASSWORD = Read-Host 'Mat khau Admin test'
   npm run test:e2e
   ```

Test xác minh tài khoản Admin mới và danh mục, phiếu, hóa đơn trống trước khi tạo fixture. Admin tạo Staff, dịch vụ, phụ tùng và nhập tồn qua API thật. Khách tạo lịch online qua `POST /api/public/bookings`; Staff thao tác trong Chromium. Playwright chỉ ngắt phản hồi sau khi backend đã commit ở ca mất kết nối. Xem [bằng chứng SC-14](../docs/plans/repair-intake-handoff.md#kết-quả-sc-14) để biết các ca đã chạy.

Sau mỗi lượt, dừng backend test và xóa database browser dùng một lần để dọn toàn bộ fixture, kể cả khi test thất bại. Dùng database mới cho lượt tiếp theo. Kết quả Playwright nằm trong thư mục cache cục bộ của repo.

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

Mỗi feature đặt component, API và type liên quan cạnh nhau.
