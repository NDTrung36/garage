# Bàn giao Hóa đơn và thanh toán

- Trạng thái: Đã triển khai và kiểm chứng luồng hóa đơn. HD-06 chưa đóng do bộ SQL Server toàn dự án còn lỗi.
- Ngày lập: 18/11/2026
- Branch/HEAD quan sát: `codex/guest-booking-public-lookup` / `b61dd5d`
- Kế hoạch: [hoa-don-thanh-toan-plan.md](hoa-don-thanh-toan-plan.md)
- Phạm vi: hóa đơn nội bộ, đọc danh sách/chi tiết, thu đủ, Admin sửa ghi nhận, audit, idempotency và giao diện. Tra cứu public chỉ bàn giao contract read-only cho Guest Booking.
- Bảo toàn worktree: lúc lập kế hoạch có nhiều thay đổi chưa commit/untracked ở repair, inventory, billing, docs và frontend. Kiểm tra `git status` trước khi làm; không stage hoặc xóa thay đổi có trước.

## Trạng thái đầu việc

| ID | Trạng thái | Bằng chứng hoặc việc còn lại |
|---|---|---|
| HD-01 | Đã kiểm chứng ở HD-06 | 6 ca `RepairCompletionIT` và 9 ca `RepairConcurrencyIT` đạt. Flyway history xác nhận V001–V009. |
| HD-02 | Đã triển khai và kiểm chứng | Truy vấn danh sách/chi tiết, dòng phiếu đã chốt và tổng hóa đơn trên chi tiết phiếu. 13 test unit/API và 1 test SQL Server đạt. HD-04 tiếp nhận phần gắn route. |
| HD-03 | Đã triển khai và kiểm chứng | Thu đủ, sửa ghi nhận, audit và idempotency. Kiểm thử SQL Server đạt cho replay, rollback, tranh khóa và retry. HD-04 tiếp nhận controller và phân quyền HTTP. |
| HD-04 | Đã triển khai và kiểm chứng | Bốn route HTTP, kiểm tra JSON, phân quyền, CSRF và thu hồi phiên. 51 test unit/MockMvc đạt. |
| HD-05 | Đã triển khai và nghiệm thu UI | Build frontend đạt; browser chạy với backend và SQL Server test riêng đạt lọc/page, điều hướng phiếu, CASH/BANK_TRANSFER/NO_CHARGE, reset Admin, Staff không có reset, Retry-After và retry cùng UUID sau khi mất response. |
| HD-06 | Đã đồng bộ tài liệu, chưa đóng | 153 unit/API, 20 SQL IT của billing và completion, migrate sạch và rerun riêng, build và browser đạt. Bộ SQL đầy đủ còn 9 ca lỗi. |

## Mốc nền đã quan sát

- V009 tạo `invoices`, unique theo code và repair order, lưu payment fields, version và index theo trạng thái/thời điểm. Migration có CHECK để bảo vệ các trường PAID, nhưng application phải bắt buộc `NO_CHARGE` khi tổng bằng 0.
- `InvoiceIssuance` tạo invoice `UNPAID`; `RepairCompletionService` chốt phiếu/booking và phát hành invoice cùng transaction. `RepairOrdersController` có endpoint complete và test API source.
- `RepairOrderQuery.detail` đang trả `invoice: null`. Chưa thấy API billing đọc/thu, UI invoice hoặc integration test completion/invoice trong danh sách file lúc lập kế hoạch.
- `FoundationMigrationIT` có schema contract và constraint cases cho invoices. Migration history thực tế chưa đọc được. `sqlcmd` tới `localhost,51433/garage_migration_test` lỗi ODBC encryption/credential, còn Maven không resolve được Spring Boot parent 3.5.16 do `Permission denied: getsockopt`. SQL Server test URL chưa đặt trong shell.
- Kế hoạch Guest Booking hiện không đưa invoice total/payment status vào public lookup. Hợp đồng tích hợp riêng chỉ trả hai trường này cho booking đã có repair order/invoice.

## Kết quả HD-01 · 19/11/2026

- Không cần sửa V009 hoặc `FoundationMigrationIT`: source hiện có schema, constraint và fixture invoices theo hợp đồng. Migration không đổi. Không đọc được Flyway history thực tế do kết nối SQL Server thất bại.
- Hoàn tất source giữ thứ tự khóa booking → repair order → invoice. Khi phiếu đã hoàn tất, request lặp đọc invoice theo `repair_order_id` và trả summary hiện tại. Phát hành mã trùng rollback transaction rồi retry mã mới tối đa ba lần.
- Đã thêm `javaHalfUpLineRoundingMatchesSqlServerRoundAtBoundaries` trong `RepairCompletionIT` để so sánh phép làm tròn Java với SQL Server tại các biên thập phân.
- Kiểm tra `mvn -q "-Dtest=RepairTotalsTest,RepairOrdersApiTest" test` thất bại trước khi đọc project do Maven Central từ chối mạng (`Permission denied: getsockopt`, parent Spring Boot 3.5.16 chưa có trong local repository). SQL Server IT chưa chạy. `sqlcmd` tới database test lỗi ODBC 17 với thông báo encryption/credential.
- Còn thiếu bằng chứng runtime: chạy `RepairCompletionIT` và `RepairConcurrencyIT` trên `garage_migration_test`, chạy test unit/API sau khi Maven dependency khả dụng, và xác nhận Flyway history trên SQL Server test chuyên dụng.

## Kết quả HD-02 · 19/11/2026

- Danh sách lọc theo trạng thái và phân trang theo thời điểm phát hành giảm dần, sau đó theo ID giảm dần. Chi tiết lấy dòng LABOR hợp lệ trước dòng PART có số lượng dương và dùng mô tả, đơn vị, giá đã chốt. Tổng tiền lấy từ hóa đơn. Tên người thu lấy qua hợp đồng StaffDirectory.
- Chi tiết phiếu hoàn tất lấy tổng và summary từ hóa đơn. Kiểm thử xác nhận báo lỗi khi phiếu đã hoàn tất thiếu hóa đơn hoặc truy vấn chi tiết hóa đơn của phiếu chưa hoàn tất.
- Chạy `mvn -o '-Dmaven.repo.local=../.cache/maven' -Psqlserver-it '-Dtest=InvoiceQueryTest,RepairOrdersApiTest,RepairTotalsTest' '-Dit.test=InvoiceSqlServerIT' verify`. 13 test unit/API và 1 integration test đạt. Flyway áp dụng V001–V009 trên database SQL Server 2022 riêng được tạo cho lượt kiểm thử và xóa sau khi chạy.

## Kết quả HD-03 · 19/11/2026

- Thu đủ dùng tổng hóa đơn và chỉ chấp nhận `NO_CHARGE` với tổng 0, `CASH` hoặc `BANK_TRANSFER` với tổng dương. Admin sửa ghi nhận với lý do đã trim. Lệnh khóa theo thứ tự idempotency → hóa đơn, kiểm version trước trạng thái, tăng version một lần và lưu `updated_at` UTC đến mili giây.
- Audit giữ snapshot trước/sau và lưu actor, lý do cùng giao dịch với hóa đơn và kết quả idempotency. Replay giữ kết quả gốc mà không ghi đè trạng thái hiện tại. `BillingErrors` ánh xạ xung đột payload sang `409` và chờ khóa quá hạn sang `503` kèm `Retry-After: 1`.
- Chạy `mvn -o '-Dmaven.repo.local=../.cache/maven' -Psqlserver-it '-Dtest=InvoicePaymentCommandsTest,BillingErrorsTest,InvoiceQueryTest,RepairOrdersApiTest,RepairTotalsTest' '-Dit.test=InvoiceSqlServerIT' verify`. 25 test unit/MockMvc và 5 test SQL Server đạt. Flyway áp dụng V001–V009 trên database test riêng và database được dọn sau khi chạy.

## Kết quả HD-04 · 19/11/2026

- Staff/Admin có thể đọc danh sách, đọc chi tiết và ghi nhận thu đủ. Admin được sửa ghi nhận thu tiền. Hai POST yêu cầu session, CSRF và `Idempotency-Key`. Controller lấy actor từ `GaragePrincipal`.
- Kiểm tra ID dương, version nguyên không âm, kiểu trường và danh sách trường cho phép. Từ chối khóa JSON trùng hoặc dữ liệu thừa sau object. `BillingErrors` trả mã lỗi và cấu trúc theo hợp đồng riêng của billing.
- Chạy `mvn -o '-Dmaven.repo.local=../.cache/maven' '-Dtest=InvoiceApiTest,InvoiceSecurityTest,BillingErrorsTest,InvoiceQueryTest,InvoicePaymentCommandsTest,RepairOrdersApiTest' test`. 51 test đạt. Test bảo mật chạy filter chain và `SessionValidityService` thật, dùng repository giả lập để kiểm phiên có `authVersion` cũ bị hủy trước lệnh ghi.

## Kết quả HD-05 · 19/11/2026

- Stitch MCP tạo hai màn hình desktop dùng chung design system `Garage Management Precision System`: [project hóa đơn và thanh toán](https://stitch.withgoogle.com/projects/2350853491462266492), gồm danh sách và chi tiết hóa đơn. Codex toàn cục đã được cấu hình dùng header `X-Goog-Api-Key` qua biến môi trường `STITCH_API_KEY`; không ghi key vào repo hoặc `config.toml`. HTTP JSON-RPC `list_projects` và tạo màn hình thành công bằng key trong biến môi trường. `codex mcp list` vẫn hiện trạng thái OAuth `Not logged in` và các công cụ Stitch của phiên hiện tại chưa nạp cấu hình mới; cần khởi động lại Codex rồi xác minh tool call trong phiên mới.
- Checkout này dùng `frontend/src/features/repair/RepairOrderDetailPage.tsx` thay cho `RepairOrderPage.tsx`; `repairApi.ts` giữ DTO invoice của phiếu, còn `partApi.ts` được tách khỏi kiểu response repair đầy đủ. Thêm invoice API/types/list/detail/payment hook/CSS và route/menu `/staff/invoices`, `/staff/invoices/{id}`.
- Danh sách có lọc trạng thái, phân trang, loading/empty/error. Chi tiết có dòng đã chốt, tổng invoice, paid amount/phương thức/người thu/thời điểm và link về phiếu. Phiếu đã có invoice có nút mở hóa đơn. UNPAID yêu cầu xác nhận tổng; tổng dương chọn CASH/BANK_TRANSFER, tổng 0 dùng NO_CHARGE. Chỉ Admin thấy reset khi PAID; lý do được trim và thông báo rõ thao tác chỉ sửa ghi nhận.
- Lệnh ghi tạo UUID và lưu payload trong `sessionStorage` trước POST, khóa gửi kép bằng ref, tôn trọng `Retry-After`, và GET lại sau thành công/replay/xung đột. Route mở lại chờ GET mới trước khi hiển thị control ghi.
- `npm run build` đạt (TypeScript và Vite). `mvn -o '-Dmaven.repo.local=../.cache/maven' -DskipTests package` đạt để chạy backend hiện tại. Browser dùng database tổng hợp riêng `garage_hd05_20261119`: 22 hóa đơn; trang 1 có 20 dòng, trang 2 có 2, lọc PAID hoạt động; navigation phiếu ↔ hóa đơn hoạt động.
- Browser xác nhận Staff thu CASH, BANK_TRANSFER và NO_CHARGE; Admin reset được hóa đơn đã thu; sau đó Staff thu lại được và không thấy control reset khi hóa đơn PAID. Kiểm thử 503 giả lập có `Retry-After: 2` giữ nút retry disabled đến hết thời gian. Khi trì hoãn GET detail, chưa có control ghi cho tới lúc dữ liệu mới về.
- Hai lượt giả lập server đã commit nhưng browser mất response (invoice 27 và 29) được retry bằng cùng UUID/payload. Sau replay và GET mới, SQL Server xác nhận mỗi invoice có `version=1`, một audit `INVOICE_MARK_PAID`, một idempotency row và trạng thái PAID. Invoice 26 có một audit reset, một audit thu lại và mỗi scope tương ứng một idempotency row.
- Đã dừng tiến trình backend/frontend do lượt này khởi chạy và xóa database test `garage_hd05_20261119`. Không chạy lại toàn bộ suite HD-01–04 trong lượt HD-05.

## Kết quả HD-06 · 19/11/2026

| Tiêu chí | Bằng chứng |
|---|---|
| AC-12 | `RepairCompletionIT` và `RepairConcurrencyIT` xác nhận tạo đúng một hóa đơn, hoàn tất lặp, tranh khóa, rollback insert và tổng giữ nguyên sau đổi giá catalog. |
| AC-23 | `RepairTotalsTest` và `RepairCompletionIT` xác nhận làm tròn từng dòng HALF_UP, so với SQL Server `ROUND` ở biên và tổng tiền đã chốt. |
| AC-13 / AC-15 | `InvoiceApiTest`, `InvoiceSecurityTest` và `InvoiceSqlServerIT` kiểm tra phương thức, actor, thời điểm, CSRF, quyền Guest/Staff/Admin và thu hồi phiên. Browser xác nhận Staff thu, không thấy biểu mẫu reset và Admin reset được. |
| AC-20 | Hai ca chờ cùng key và rollback rồi xử lý lại của `IdempotencySqlServerIT` đạt. `InvoiceSqlServerIT` xác nhận replay, rollback audit, retry cùng key, đua version và chờ khóa quá hạn. Browser/DB xác nhận mất phản hồi sau commit vẫn chỉ ghi một lần. |
| AC-16 · Flyway | Ở hai DB trống riêng, Flyway history có V001–V009 đều thành công. Ca `FoundationMigrationIT#migrationsAreValidatedAndNotReapplied` chạy riêng trên một cặp DB mới đạt, xác nhận rerun chạy 0 migration và nâng V004 → V009 với fixture. Ca tương ứng trong bộ đầy đủ vẫn lỗi ở điều kiện seed `generated_through IS NULL`; lần chạy riêng không tái hiện. |
| AC-14 và AC-16 · vận hành | Đã bàn giao hai trường projection chỉ đọc cho [Guest Booking](guest-booking-public-lookup-handoff.md#projection-thanh-toán--ac-14). Restore thuộc nghiệm thu vận hành. Dùng backup thực của môi trường đích để khôi phục sang DB riêng, kiểm tra Flyway history và đối chiếu invoice/audit. Chưa có bằng chứng restore. |

- Backend Java 21.0.12. Trong `backend`, `mvn -o '-Dmaven.repo.local=../.cache/maven' test` đạt 153/153 test. Trong `frontend`, `npm run build` đạt TypeScript và Vite.
- SQL Server Express 2022 `16.0.1000.6`, JDBC Windows integrated authentication qua port 51433. Trong `backend`, chạy `mvn -o '-Dmaven.repo.local=../.cache/maven' -Psqlserver-it '-Dspring-boot.repackage.skip=true' verify` với `MIGRATION_TEST_URL` và `MIGRATION_TEST_V004_URL` trỏ vào hai DB riêng trống. Bỏ đóng gói lại executable JAR để chạy song song với backend demo. Unit/API đạt, SQL IT đạt 61/70, gồm `InvoiceSqlServerIT` (5), `RepairCompletionIT` (6) và `RepairConcurrencyIT` (9).
- Chín ca đỏ gồm thu hồi session kho (1), inventory (3), phiếu xuất/hoàn (1), walk-in (1), seed Flyway (1), và idempotency chung (2: fixture mã phụ tùng dài hơn cột và truy vấn SHOWPLAN JDBC không trả result set). Cần xử lý rồi chạy lại profile đầy đủ trước khi đóng HD-06.
- Chạy lại lệnh profile với `'-Dtest=InvoiceSecurityTest' '-Dit.test=FoundationMigrationIT#migrationsAreValidatedAndNotReapplied'` trên hai DB trống khác. 4 API/security và 1 SQL IT đạt. Các DB integration tạm đã được dọn.
- Playwright Chromium qua Vite 5173, Spring Boot 8080 và DB tổng hợp riêng `garage_ui_demo`. Staff gửi thu BANK_TRANSFER cho `INV-UIDM00000024` (32.000 đồng, version 0). Playwright chuyển POST đầu đến backend thật, nhận thành công rồi ngắt phản hồi phía browser. Retry dùng cùng UUID và payload. Sau đó Admin reset với lý do. SQL Server xác nhận hóa đơn `UNPAID`, version 2, bốn trường paid null. Audit có đúng một dòng Staff thu (0 → 1) và một dòng Admin reset (1 → 2), mỗi scope có đúng một idempotency record HTTP 200, gồm đúng một dòng cho UUID đã retry. Giữ môi trường demo để người dùng tiếp tục thử.

## Sửa lỗi sau review

- Biểu mẫu sửa ghi nhận giữ version lúc mở. Nếu tải lại thấy version khác, Admin phải đối chiếu khoản thu mới và mở lại biểu mẫu.
- Sau POST thành công, lỗi GET chỉ yêu cầu tải lại chi tiết. Trang dùng chung cơ chế hủy lượt đọc cũ cho tải ban đầu, tải thủ công và tải sau thao tác. Staff không khôi phục hoặc gửi lại lệnh reset đã lưu.
- Danh sách tự lùi về trang hợp lệ khi kết quả lọc bị thu hẹp. CSS thanh điều hướng chỉ áp vào sidebar để thẻ thanh toán giữ màu chữ và bố cục riêng.
- `npm run build` đạt. Chưa chạy lại các tình huống hồi quy trên trình duyệt hoặc bộ nghiệm thu SQL Server.

## Kiểm tra khi tiếp tục

Đọc `AGENTS.md`, `skills/garage-feature-acceptance/SKILL.md`, `skills/garage-sqlserver-migration/SKILL.md`, [kế hoạch sửa chữa](repair-intake-plan.md) và [handoff sửa chữa](repair-intake-handoff.md). Kiểm tra lại branch, diff và files trong worktree. Trước migration, kiểm tra scripts và `flyway_schema_history`, dùng database SQL Server test riêng theo `backend/README.md`. Phân biệt unit/API, SQL Server integration, build và browser acceptance khi cập nhật bảng trạng thái.
