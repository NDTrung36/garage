# Kế hoạch triển khai Hóa đơn và thanh toán

Ngày 18/11/2026 · Repo `D:\Project\University\garage-sua-xe` · Branch quan sát `codex/guest-booking-public-lookup` · HEAD `b61dd5d`.

## Mục tiêu và phạm vi

Staff/Admin xem hóa đơn nội bộ, đối chiếu công việc và phụ tùng đã chốt rồi xác nhận đã thu đủ. Admin có thể sửa ghi nhận thu nhầm, bắt buộc lý do và audit. Mỗi phiếu hoàn tất sinh đúng một hóa đơn, kể cả tổng bằng 0 VND. Đây không phải hóa đơn điện tử và không tích hợp ngân hàng.

Worktree lúc lập kế hoạch có V009, `InvoiceIssuance`, `RepairCompletionService` và API hoàn tất phiếu dưới dạng thay đổi chưa commit. GET chi tiết phiếu hiện vẫn trả `invoice: null`; chưa có API đọc/thu hóa đơn hoặc giao diện hóa đơn. Không có `MIGRATION_TEST_URL` trong shell lúc kiểm tra. Chưa đọc `flyway_schema_history` trên DB.

Tra cứu thanh toán cho khách được bàn giao riêng cho Guest Booking. Hợp đồng cần tích hợp sau là chỉ đọc `totalAmount` và `paymentStatus` của phiếu đã có hóa đơn, không trả mã hóa đơn, người thu, phương thức, lý do hay audit. Không tạo migration mới trừ khi kiểm tra lúc triển khai phát hiện contract/schema hiện tại không đáp ứng được. Kiểm tra version Flyway thực tế trước mọi thay đổi schema.

## Hợp đồng API

| API | Hợp đồng |
|---|---|
| `GET /api/staff/invoices` | Staff/Admin. Query tùy chọn `paymentStatus=UNPAID\|PAID`, `page` mặc định 0, `size` mặc định 20, tối đa 100. Sắp `issuedAt DESC, id DESC`. Trả `{items,page,size,totalElements,totalPages}`. Item gồm `id,code,repairOrderId,issuedAt,totalAmount,paymentStatus`. |
| `GET /api/staff/invoices/{id}` | Staff/Admin. Trả header gồm `version,paidAmount,paymentMethod,paidBy,paidByDisplayName,paidAt,items`. Dòng gồm `id,type,description,unit,quantity,unitPrice,lineTotal`. Trường thanh toán null ở trạng thái `UNPAID`. |
| `POST /api/staff/invoices/{id}/mark-paid` | Staff/Admin. Nhận `{expectedVersion,paymentMethod}`. Tổng dương chỉ nhận `CASH` hoặc `BANK_TRANSFER`; tổng 0 chỉ nhận `NO_CHARGE`. Server tự lấy tổng, actor từ session và thời điểm từ `Clock`. |
| `POST /api/admin/invoices/{id}/reset-payment` | Admin. Nhận `{expectedVersion,reason}`. Sau trim, lý do dài 1–500 ký tự. Chuyển `PAID → UNPAID`, xóa bốn trường paid khỏi invoice và giữ snapshot cũ trong audit. |

Hai POST cần session, CSRF và `Idempotency-Key`. Thành công trả `200` với `{id,paymentStatus,paidAmount,paymentMethod,paidBy,paidAt,version}`; dùng ID dạng chuỗi, tiền số nguyên VND trong safe integer, lượng dạng chuỗi scale 3, thời gian ISO 8601 có offset. Response thành công dùng `Cache-Control: no-store`. Chỉ chấp nhận các field đã định nghĩa.

Lỗi nghiệp vụ: `400 INVALID_PAYMENT_METHOD`, `INVALID_PAYMENT_RESET_REASON`, `INVALID_INPUT`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_IDEMPOTENCY_KEY`; `404 INVOICE_NOT_FOUND`; `409 STALE_VERSION`, `INVOICE_ALREADY_PAID`, `INVOICE_NOT_PAID`, `IDEMPOTENCY_CONFLICT`; `503 REQUEST_IN_PROGRESS` kèm `Retry-After: 1`. Giữ `401/403` của security chain. Lỗi invariant trả `500 INVOICE_DATA_INCONSISTENT` với payload chuẩn `code,message,fieldErrors,traceId`, không lộ SQL.

## Subtask triển khai

Quy ước `J=backend/src/main/java/vn/garage`, `T=backend/src/test/java/vn/garage`, `F=frontend/src`.

### HD-01 — Xác nhận schema và hoàn tất phiếu

- **File:** `backend/src/main/resources/db/migration/V009__create_invoices.sql`, `T/migration/FoundationMigrationIT.java`, `J/repair/application/RepairCompletionService.java`, `J/billing/application/InvoiceIssuance.java`, `T/repair/infrastructure/RepairCompletionIT.java`, `T/repair/api/RepairOrdersApiTest.java`.
- **Bước:** Đối chiếu SC-11 và migration scripts; kiểm tra `flyway_schema_history` trên SQL Server test riêng. Dùng schema V009/luồng sinh hóa đơn hiện có nếu hợp lệ. Giữ thứ tự khóa booking → phiếu → invoice. V009 hiện cho phép `CASH`/`BANK_TRANSFER` cho tổng 0, vì vậy application phải áp quy tắc `NO_CHARGE` riêng.
- **Test/đạt:** SQL Server chứng minh hoàn tất sinh một `UNPAID`, tổng 0 hợp lệ, `0.500 × 101` chốt 51 VND, đổi giá danh mục không đổi tổng, hai request hoàn tất chỉ tạo một hóa đơn, lỗi insert rollback booking/phiếu. Hoàn tất lặp trả invoice summary hiện tại. So sánh Java với SQL Server `ROUND` trên các biên tiền.

### HD-02 — Đọc danh sách/chi tiết và nối hóa đơn vào phiếu

- **File:** sửa `J/billing/domain/InvoiceStore.java`, `J/billing/infrastructure/JdbcInvoiceStore.java`, `J/billing/application/InvoiceIssuance.java`; thêm `J/billing/application/InvoiceQuery.java`, `J/repair/application/RepairInvoiceLines.java`; sửa `J/repair/application/RepairOrderQuery.java`. Test `T/billing/application/InvoiceQueryTest.java`, `T/billing/infrastructure/InvoiceSqlServerIT.java`.
- **Bước:** Mở rộng store đọc invoice theo ID và danh sách phân trang, giữ nguyên `InvoiceIssuance.Summary` đang dùng bởi completion API. Query gọi contract repair để lấy các dòng đã chốt và `StaffDirectory` để phân giải tên người thu. Dòng LABOR đứng trước PART rồi sắp theo ID; bỏ LABOR void và PART quantity 0. Dùng quy tắc làm tròn hiện tại cho từng dòng, tổng invoice đã lưu làm nguồn chuẩn. Completion query trả invoice summary trên phiếu COMPLETED.
- **Test/đạt:** Filter/page/sắp xếp ổn định khi trùng timestamp; hóa đơn 0 vẫn đọc được khi không có dòng; dữ liệu giữ snapshot sau đổi catalog; phiếu và invoice khớp tổng/trạng thái; detail không trả ghi chú nội bộ, ledger hoặc thông tin audit. Phiếu COMPLETED thiếu invoice hoặc invoice gắn với phiếu chưa hoàn tất báo invariant.

### HD-03 — Thu đủ, sửa ghi nhận, audit và idempotency

- **File:** thêm `J/billing/domain/InvoicePayment.java`, `J/billing/application/InvoicePaymentCommands.java`; mở rộng `InvoiceStore.java`, `JdbcInvoiceStore.java`; test `T/billing/application/InvoicePaymentCommandsTest.java`, `T/billing/infrastructure/InvoiceSqlServerIT.java`.
- **Bước:** Dùng `IdempotencyExecutor` với scope `billing.invoice.mark-paid` và `billing.invoice.reset-payment`, scope theo actor. Canonical payload gồm invoice ID, version và phương thức hoặc lý do đã trim. Với key mới, khóa invoice PK bằng `UPDLOCK,HOLDLOCK`, kiểm version rồi trạng thái và phương thức. Thứ tự khóa idempotency → invoice. Cập nhật payment fields/`updated_at`, tăng version một lần và ghi UTC millis.
- **Bước:** Dùng `AuditWriter` trong cùng transaction. `entity_type=INVOICE`, action `INVOICE_MARK_PAID` hoặc `INVOICE_RESET_PAYMENT`. Snapshot trước/sau có ID, tổng, trạng thái, các trường paid và version; actor/reason lưu cột audit hiện có.
- **Test/đạt:** Kiểm các phương thức theo tổng dương/0, reason trim/giới hạn, replay cùng key một update/audit, khác payload 409, hai key cùng version chỉ một thành công, reset rồi thu lại, replay response cũ không ghi lùi trạng thái hiện hành. Lỗi audit rollback payment/version/idempotency. Request đầu rollback cho phép cùng key thử lại. Chạy đua bằng hai transaction SQL Server độc lập; kiểm lock timeout/retry header.

### HD-04 — API và bảo mật

- **File:** thêm `J/billing/api/StaffInvoicesController.java`, `AdminInvoicePaymentsController.java`, `BillingErrors.java`; test `T/billing/api/InvoiceApiTest.java`, `InvoiceSecurityTest.java`.
- **Bước:** Expose bốn route ở trên. Parse ID dương, version không âm, JSON và field allowlist chặt. Actor chỉ lấy từ `GaragePrincipal`. Tái dùng security matcher `/api/staff/**`, `/api/admin/**`, session và CSRF hiện có. Ánh xạ lỗi bằng advice chỉ áp dụng controller billing.
- **Test/đạt:** MockMvc kiểm DTO, status, headers và mã lỗi. Qua `SecurityFilterChain` thật: guest bị chặn; Staff đọc/thu được; Staff reset bị 403 dù có CSRF; Admin reset được; POST thiếu CSRF bị chặn trước use case; phiên bị thu hồi không thể ghi. Từ chối `paidAmount`, `paidBy`, `paidAt` trong request.

### HD-05 — Giao diện hóa đơn

- **File:** thêm `F/features/invoice/api.ts`, `types.ts`, `InvoicesPage.tsx`, `InvoicePage.tsx`, `useInvoicePayment.ts`, `invoice.css`; sửa `F/app/App.tsx`, `F/features/repair/RepairOrderPage.tsx`, `F/features/repair/partApi.ts`.
- **Bước:** Thêm menu Staff/Admin và route `/staff/invoices`, `/staff/invoices/{id}`. Danh sách lọc trạng thái, phân trang, loading/empty/error. Chi tiết hiển thị dòng, tổng chốt, thông tin thu và link về phiếu. Phiếu hoàn tất hiện mã, tổng, trạng thái và link invoice.
- **Bước:** Với UNPAID, hiện xác nhận thu đủ; tổng dương yêu cầu chọn phương thức và xác nhận tổng; tổng 0 dùng nút xác nhận miễn phí. Chỉ Admin thấy reset khi PAID, kèm form reason và mô tả thao tác chỉ sửa ghi nhận. Gửi qua API client hiện có. Giữ UUID/payload trong `sessionStorage` cho retry sau mất response, chặn gửi kép, tôn trọng `Retry-After`, rồi GET lại trạng thái sau success/replay/conflict. Khi quay lại route, tải dữ liệu mới trước khi cho ghi.
- **Test/đạt:** `npm run build`; browser kiểm lọc/page, navigation từ phiếu, thu CASH/BANK_TRANSFER, miễn phí, reset Admin và không có control reset ở Staff. Mô phỏng POST đã commit nhưng mất response, retry cùng UUID và DB có đúng một lần ghi.

### HD-06 — Nghiệm thu và đồng bộ tài liệu/handoff

- **File:** cập nhật `docs/requirements.md` §6, `docs/architecture.md` §3.4–3.5 và §4.3, `docs/erd.md` §3.8; cập nhật `docs/plans/hoa-don-thanh-toan-handoff.md`, handoff sửa chữa và Guest Booking trong `docs/plans/`.
- **Bước:** Ghi API, audit, quy tắc phương thức, hành vi UI và contract read-only để Guest Booking tích hợp. Chạy `mvn test`, `mvn -Psqlserver-it verify`, `npm run build`, rồi browser với backend và SQL Server thật. Dùng dữ liệu/tài khoản tổng hợp; đối chiếu invoice, version, audit và idempotency trong DB sau thu/reset.
- **Đạt:** Gắn AC-12/23 với completion/tổng, AC-13/15 với thu và quyền, AC-20 với replay/rollback, phần migration AC-16 với migrate sạch/rerun. Ghi rõ lệnh, kết quả và môi trường từng lớp. Bàn giao projection AC-14 cho Guest Booking, phần restore AC-16 cho nghiệm thu vận hành. Chỉ đánh dấu hoàn tất khi các tiêu chí bắt buộc có bằng chứng.

## Thứ tự và tiêu chí tổng

`HD-01 → (HD-02 và HD-03) → HD-04 → HD-05 → HD-06`.

Hoàn tất khi một phiếu chỉ có một hóa đơn bất biến; Staff/Admin xem và Staff/Admin thu đủ đúng quy tắc; chỉ Admin sửa ghi nhận với audit; các lệnh chịu được retry, race và rollback trên SQL Server; giao diện và security chain đạt; handoff ghi đúng bằng chứng. Không gồm hóa đơn điện tử, gateway, đặt cọc, thu từng phần, giảm giá, thuế hoặc hoàn tiền thực tế.
