# Bàn giao — Đặt lịch và tra cứu công khai

- Trạng thái: `IMPLEMENTED_AWAITING_BROWSER_ACCEPTANCE`
- Branch: `codex/guest-booking-public-lookup`
- Commit nền: `b61dd5d` (`feat(schedule): add garage schedule management baseline`)
- Kế hoạch: [guest-booking-public-lookup.md](guest-booking-public-lookup.md)
- Phạm vi: guest booking + public lookup; implementation chưa commit, không push.
- Bảo toàn: file chưa track `backend/src/main/java/vn/garage/inventory/application/PartCatalog.java` không thuộc feature, giữ nguyên và không stage.

## Trạng thái task

| ID | Trạng thái | Bằng chứng / còn lại |
|---|---|---|
| GB-01 | DONE | `customer/domain/CustomerContactRules.java`, `repair/domain/GuestBookingRequest.java`; unit tests phone/validation pass. |
| GB-02 | DONE | `GuestCustomerService` + `JdbcGuestCustomerRepository` upsert trong transaction; SQL Server scenario xác nhận lưu và tái sử dụng customer/vehicle. |
| GB-03 | DONE | `BookingServiceCatalog`, `BookingSlotPolicy`, `JdbcBookingSlotRepository`; slot lock/policy và capacity race được kiểm tra trên SQL Server. |
| GB-04 | IMPLEMENTED | `GuestBookingService` dùng executor, store và job dọn idempotency dùng chung. Unit test replay đạt. Cần chạy lại SQL Server integration trên triển khai hiện tại. |
| GB-05 | DONE | `PublicBookingLookup` + `JdbcPublicBookingLookupRepository`; unit test projection/lookup pass; SQL Server test tra cứu bằng phone pass. |
| GB-06 | DONE | `PublicBookingController`, `PublicBookingErrors`, `PublicBookingRateLimitFilter`, `SecurityConfiguration`; API/filter tests pass. |
| GB-07 | IMPLEMENTED | Build đạt. Chromium với API giả lập xác nhận slot đổi ngày, lỗi tải và tải lại khi hết chỗ. Còn luồng với backend thật. |
| GB-08 | IMPLEMENTED | Build đạt. Chromium với API giả lập xác nhận phản hồi của chế độ tra cứu cũ không ghi đè kết quả mới. Còn luồng với backend thật. |
| GB-09 | PARTIAL | 26 unit/API test và frontend build đạt sau bản sửa review. Còn nghiệm thu trình duyệt với backend thật và chạy lại SQL Server integration cho triển khai hiện tại. |

## Kết quả kiểm tra

- `mvn -DskipTests package`: pass, backend compile/package thành công.
- Lượt triển khai trước dùng `mvn -Psqlserver-it` với `FoundationMigrationIT` và ghi nhận 20 integration test SQL Server đạt. Kết quả đó có trước bản idempotency dùng chung hiện tại.
- `npm run build`: pass, gồm TypeScript typecheck và Vite production build.
- `git diff --check`: pass.
- Browser acceptance: chưa chạy. SQL Server Express cho phép `sqlcmd` qua local instance, nhưng JDBC tới TCP listener `127.0.0.1:51433` timeout/SSPI lỗi; SQL Server Browser named-instance lookup cũng timeout. Không thay cấu hình SQL Server/firewall để né blocker.
- Hai database test do lượt này tạo (`garage_migration_test_guestbooking`, `garage_migration_upgrade_test_guestbooking`) đã được xóa sau test.
- Full unfiltered Maven test run có 3 lỗi trong `InventoryApiTest` (Authentication null ở PartController), ngoài phạm vi Guest Booking và thuộc thay đổi inventory chưa commit; không chỉnh hay stage các file đó.

## Kiểm chứng sau khi sửa review

- `mvn -o '-Dmaven.repo.local=../.cache/maven' '-Dtest=PublicBookingApiTest,GarageScheduleApiTest,PublicBookingRateLimitFilterTest,GuestBookingServiceTest,PublicBookingLookupTest,BookingSlotPolicyTest,GuestBookingRequestTest,CustomerContactRulesTest,RepairErrorsTest' test` chạy từ backend. 26 test đạt, gồm từ chối key sai định dạng, trả 503 khi lỗi mở transaction được giả lập và giữ `Retry-After` của API slot.
- Chạy lại 26 test trên bản sao tạm cũng đạt. Lượt này chỉ lấy các test liên quan và loại hai file đang thay đổi ngoài feature khỏi bản sao (`StaffStockMovementsController`, `InventoryErrors`). Lượt chạy lại trên toàn checkout gặp lỗi compile từ thay đổi đồng thời ở các module khác.
- `npm run build` đạt. Playwright chạy Chromium trên bản build với API giả lập. Đã kiểm tra phản hồi slot về trễ, lỗi tải ngày mới, tải lại khi hết chỗ và giữ form, đổi chế độ tra cứu khi request còn chạy, cùng khôi phục phiên nhân viên sau lỗi tạm thời bằng nút Back.
- Chưa chạy lại SQL Server integration hoặc luồng xuyên hệ thống trên trình duyệt trong lượt sửa review.

## Ghi chú triển khai

Kịch bản SQL Server của booking nằm trong `FoundationMigrationIT`.

## Kiểm chứng sau lần review thứ hai

- 17 test trong `PublicBookingApiTest`, `GuestBookingServiceTest` và `IdempotencyExecutorTest` đạt qua Maven. `npm run build` đạt.
- Chromium với API giả lập xác nhận giữ nguyên key và payload khi body `201` bị cắt hoặc thiếu dữ liệu, chặn đặt lịch đến khi khách duyệt lại dịch vụ, và cho thử lại khi tải lịch lỗi mà không báo hết chỗ.
- SQL Server chạy câu dọn mới trên bảng tạm với batch 1. Chỉ bản ghi hết hạn cũ nhất bị xóa, bản ghi còn hạn được giữ. Chưa chạy lại toàn bộ SQL Server integration hoặc luồng trình duyệt với backend thật.

Đã chuyển checkout sang branch `codex/guest-booking-public-lookup` từ commit nền `b61dd5d`. Các file inventory/repair khác đang modified hoặc untracked được giữ nguyên và không stage.

## Projection thanh toán · AC-14

Hợp đồng bàn giao ngày 19/11/2026. Chưa tích hợp vào `POST /api/public/lookups`.

- Giữ tra theo số điện thoại đã chuẩn hóa và phân trang theo booking. Tra mã chính xác chỉ trả tối đa một booking. Giữ che biển số và allowlist public hiện có.
- Sau khi xác định booking trong trang trả về, đọc theo quan hệ booking → repair order → invoice. Chỉ khi có hóa đơn mới bổ sung `totalAmount` (số nguyên VND, gồm tổng 0) và `paymentStatus` (`UNPAID` hoặc `PAID`). Booking chưa có hóa đơn giữ response hiện tại và không suy diễn là chưa thanh toán.
- Projection chỉ đọc. DTO không có ID/mã hóa đơn, người thu, phương thức, thời điểm thu, lý do, ledger hoặc audit. Khi tích hợp, kiểm thử tra mã, phân trang theo SĐT, tổng 0, trước/sau reset và allowlist response. Xem [hợp đồng kiến trúc](../architecture.md#43-staff-và-admin).

## Quy tắc tiếp tục

Đọc [AGENTS.md](../../AGENTS.md), [garage-schedule-booking](../../skills/garage-schedule-booking/SKILL.md), [garage-sqlserver-migration](../../skills/garage-sqlserver-migration/SKILL.md) và [garage-feature-acceptance](../../skills/garage-feature-acceptance/SKILL.md). Chạy browser acceptance khi JDBC local kết nối được SQL Server; sau đó cập nhật trạng thái thành `COMPLETE`. Không xem build là bằng chứng browser acceptance.
