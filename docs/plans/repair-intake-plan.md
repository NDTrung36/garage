# Kế hoạch triển khai Tiếp nhận xe và sửa chữa

Ngày lập 18/11/2026 · Trạng thái **Sẵn sàng bàn giao kế hoạch** · Mốc khảo sát `main` tại `e815d85`, gồm các thay đổi lịch và danh mục đang có trong worktree. [Bàn giao](repair-intake-handoff.md).

## 1. Kết quả cần đạt

Nhân viên tiếp nhận lịch online hoặc xe đến trực tiếp, mở đúng một phiếu `IN_PROGRESS`, ghi tình trạng xe và công việc thực tế, xuất/hoàn phụ tùng, rồi hoàn tất để sinh đúng một hóa đơn `UNPAID`. Admin thao tác được như Staff. Sau khi chốt, phiếu chỉ đọc và tiền được cố định.

| Phạm vi | Đầu ra |
|---|---|
| Tiếp nhận | Danh sách lịch nội bộ, check-in online, form walk-in, lưu người/thời điểm nhận và dữ liệu xe lúc nhận. |
| Phiếu sửa | Danh sách, chi tiết, ghi tình trạng/số km/ghi chú riêng, thêm/sửa/bỏ công việc và tính tiền tạm. Giữ danh sách dịch vụ đã đặt. |
| Vật tư trên phiếu | Đọc danh mục/tồn, xuất/hoàn theo lần xuất gốc, xem lượng còn được hoàn, đổi giá dòng PART có lý do và audit. Dùng API tạo part/nhập tồn của feature kho để chuẩn bị phụ tùng. |
| Hoàn tất | Chốt phiếu, booking và hóa đơn trong cùng transaction. Hiển thị mã, tổng tiền và trạng thái hóa đơn ngay trên phiếu. |
| Feature liên quan bàn giao riêng | Guest Booking và tra cứu public, đổi/hủy/no-show, hồ sơ khách/xe đầy đủ, màn hình quản trị kho và điều chỉnh tồn, thu tiền/sửa nhầm thu tiền, triển khai và backup/restore production. |

Đối chiếu [đặc tả §2, §3.4 và §4–6](../requirements.md#4-tiếp-nhận-và-sửa-chữa), [transaction và API](../architecture.md#3-luồng-chính-và-transaction) và [ERD sửa chữa](../erd.md#36-repair-repair_orders-và-repair_items).

## 2. Mốc hiện tại và phụ thuộc

| Bằng chứng trong worktree | Hệ quả khi triển khai |
|---|---|
| `V001` có `users`, `customers`, `vehicles`, `services`. `V002` có `appointment_slots`, `bookings`, `booking_services`, `idempotency_records`. `V003` seed cấu hình. `V004` của worktree thêm override slot và version dịch vụ. | Dùng schema nền và snapshot hiện có. Chốt số migration tiếp theo sau khi đồng bộ với nhánh lịch. |
| `ServiceCatalog` và `ServiceController` có API đọc service theo loại xe và API Admin đọc/tạo/sửa danh mục. `SlotGenerator`, `ScheduleJobs` và các controller lịch đã có trong worktree. | Tái dùng API danh mục và gọi hợp đồng `catalog.application` từ `repair`. Đối chiếu khóa hàng slot và bộ trạng thái chiếm chỗ với tác vụ lịch. |
| `SecurityConfiguration` đã có matcher Staff/Admin, CSRF `X-CSRF-TOKEN` và `SessionValidityFilter`. `application.yml` đã dùng Hibernate `validate`. | Tái dùng session/principal, kiểm tra qua filter chain thật và giữ Flyway quản lý schema. |
| Chưa có controller tạo booking online, use case customer/vehicle, hoặc bảng/API vận hành repair, inventory, billing và audit. `idempotency_records` mới có schema và test JDBC nền. | Xây hợp đồng tìm/tạo hồ sơ dùng chung cho walk-in và Guest Booking. Thêm hỗ trợ idempotency dùng chung, các bảng và API riêng của feature. |
| `App.tsx` đang điều hướng bằng History API và có màn hình đăng nhập/quản lý user. `client.ts` đã gửi cookie, CSRF và phát sự kiện khi nhận `401`. Các thư mục feature chỉ có `.gitkeep`, `package.json` chưa có script E2E. | Gắn màn hình Staff vào shell hiện tại và dùng client chung. Thêm kịch bản E2E riêng có lệnh chạy. |

**Phụ thuộc luồng online.** Nhánh Guest Booking phải tạo được `ONLINE/CONFIRMED` với `slot_id`, snapshot và ít nhất một `booking_services`, rồi cung cấp cách lấy booking ID cho Staff qua danh sách. Dùng fixture SQL tổng hợp đúng hợp đồng để phát triển và kiểm thử check-in sớm. Kết quả này chứng minh luồng từ check-in trở đi. Kịch bản xuyên hệ thống phải chạy lại với booking tạo qua `POST /api/public/bookings` thật khi API đó sẵn sàng. Tận dụng hợp đồng `CustomerVehicleOnboarding`, `BookingStore` và idempotency khi nhánh Guest Booking đã có phần tương đương.

**Phối hợp với feature kho.** [Kế hoạch Phụ tùng và tồn kho](phu-tung-ton-kho.md) có phần trùng về schema, idempotency, xuất/hoàn và panel vật tư. Đề xuất INV-02 sở hữu schema chung V005–V007, SC-02 thêm hóa đơn, INV-04 sở hữu idempotency dùng chung với SC-03, INV-03/05 cung cấp danh mục và nhập tồn. SC-09–10 phối hợp INV-06 để triển khai một bộ endpoint và một bộ adapter, SC-13 phối hợp INV-09 để gắn một panel. Nếu sửa chữa làm trước, nhận các đầu việc chung ở cùng vị trí file dự kiến và cập nhật link bằng chứng ở cả hai handoff khi tích hợp. Chốt người thực hiện và phiên bản thật ở SC-01/INV-01 trước khi hai nhánh sửa cùng schema.

## 3. Quy ước dành cho người triển khai

### Các điểm cần xác nhận ở SC-01

Các mặc định dưới đây đủ để triển khai theo kế hoạch. Nếu maintainer chọn hành vi khác, ghi lựa chọn vào bàn giao và đồng bộ tài liệu/tests của subtask liên quan trước khi phát hành.

| Điểm cần chốt | Mặc định đề xuất |
|---|---|
| Dịch vụ đặt trước khi tiếp nhận | Hiển thị riêng để đối chiếu. Phiếu bắt đầu với danh sách công việc trống. Nhân viên có thể lấy tên/giá gợi ý từ danh mục khi thêm LABOR và lưu giá thực tế. Lịch cũ vẫn tiếp nhận được khi dịch vụ đã ngừng hoạt động. |
| Tên, giá và số lượng LABOR | Thêm dòng có `quantity`; khi sửa cho phép đổi mô tả, số lượng và đơn giá trước khi chốt. Giữ `serviceId` và `unitSnapshot` sau khi tạo dòng. LABOR dùng đơn vị mặc định `công`. |
| Kiến trúc có `DELETE` LABOR, đặc tả giữ lịch sử | Đề xuất lưu `voided_at` và `voided_by` trên dòng LABOR đã bỏ. API `DELETE` trả `204`; danh sách làm việc và tổng tiền chỉ tính dòng còn hiệu lực. Đối chiếu/audit giữ dữ liệu gốc. |
| Đặc tả nhập kho yêu cầu lý do và ghi chú, ERD chỉ có `reason` | Theo hợp đồng kho, bổ sung `note NVARCHAR(500) NULL` vào schema ledger mới. Nhập kho bắt buộc `reason`, `note` tùy chọn. ISSUE/RETURN có lý do mặc định do server lập. |
| Tiếp nhận sớm/muộn, và khách dừng sửa | Cho check-in mọi lịch online còn `CONFIRMED`, kể cả slot đã đóng nhận thêm hoặc đã qua giờ. Khi khách dừng, hoàn phần vật tư chưa dùng rồi hoàn tất theo phần thực tế. Phiếu trống sinh hóa đơn tổng `0`, vẫn `UNPAID`. |

### Trạng thái và hợp đồng HTTP

Mọi path ở bảng có prefix `/api`. ID và FK JSON là chuỗi số nguyên dương, parse có kiểm tra phạm vi BIGINT. `expectedVersion` là số nguyên không âm theo kiểu hiện dùng trên API lịch. Timestamp ISO 8601 có offset, lưu UTC và UI hiển thị `Asia/Ho_Chi_Minh`. Tiền JSON là số nguyên VND an toàn với JavaScript. Request gửi `quantity` dạng chuỗi thập phân như `"0.500"`, tối đa 15 chữ số phần nguyên và 3 chữ số phần lẻ, dùng `BigDecimal` để parse và tính. API trả các trường số lượng dạng chuỗi thập phân với scale 3. Dữ liệu nội bộ có `Cache-Control: no-store`.

| Method/path | Input và kết quả |
|---|---|
| `GET /staff/bookings` | `date` theo giờ garage, `status`, `page`, `size`. Trả booking ID/code, source/status/version, slot/giờ nhận, liên hệ và xe từ snapshot, `requestedServices`, `repairOrderId` nếu có. Lọc ngày theo giờ slot online hoặc giờ nhận walk-in. Màn hình mặc định ngày hiện tại và `CONFIRMED`, cho đổi ngày/trạng thái. |
| `POST /staff/bookings/{id}/check-in` | `{expectedVersion, odometerKm?, conditionNotes?, internalNotes?}`. `200` với ID/mã/trạng thái booking, ID/trạng thái/version phiếu và `receivedAt`. `CONFIRMED` tạo phiếu; `CHECKED_IN`/`COMPLETED` đã có phiếu trả trạng thái hiện tại kể cả version ban đầu, giữ nguyên dữ liệu nhận. `CANCELLED`/`NO_SHOW` trả `409`. |
| `POST /staff/walk-ins` | `{contact:{fullName,phone}, vehicle:{type,plateNumber,brand?,model?}, serviceIds, note?, odometerKm?, conditionNotes?, internalNotes?}` và `Idempotency-Key`. `201` cùng hình dạng kết quả check-in. Snapshot lấy từ lần nhập hiện tại; hồ sơ khớp được dùng lại, thay đổi hồ sơ qua nghiệp vụ customer riêng. |
| `GET /staff/repair-orders` | `status`, `page`, `size`; trả ID phiếu, booking ID/code, xe, thời điểm nhận, trạng thái/version và tổng phí. Mặc định UI chọn `IN_PROGRESS`. |
| `GET /staff/repair-orders/{id}` | `200` trả header phiếu, người/thời điểm nhận và chốt, tình trạng/số km/ghi chú nội bộ, booking snapshot, `requestedServices`, `laborItems`, `partItems`, `returnableIssues`, `totals:{laborAmount,partAmount,totalAmount}` và `invoice` nullable. Dòng PART giữ quantity `0.000`. `returnableIssues` gồm `id,repairItemId,partId,issuedQuantity,remainingQuantity,createdAt`. |
| `PATCH /staff/repair-orders/{id}` | `{expectedVersion, odometerKm?, conditionNotes?, internalNotes?}`. Trường bỏ qua giữ giá trị, `null` xóa trường tùy chọn; cần ít nhất một trường. `200` trả chi tiết mới. |
| `POST /staff/repair-orders/{id}/labor-items` | `{expectedVersion,serviceId?,description,quantity,unitPrice}`. `201` trả `{itemId,repairOrderId,version,totals}`. `serviceId` có thể bỏ trống cho công việc phát sinh. |
| `PATCH /staff/repair-orders/{id}/labor-items/{itemId}` | `{expectedVersion,description?,quantity?,unitPrice?}`; ít nhất một trường. `200` trả cùng hình dạng POST. Scoping item theo phiếu và `LABOR`. |
| `DELETE /staff/repair-orders/{id}/labor-items/{itemId}` | `expectedVersion` qua query. `204`, UI tải lại chi tiết để nhận version/tổng mới. |
| `GET /staff/parts` và `GET /staff/stock-movements` | Dùng `PartView`, `MovementView` và bộ lọc phân trang của [hợp đồng kho](phu-tung-ton-kho.md#3-hợp-đồng-triển-khai). Khi chọn part trên phiếu, gửi `active=true` và `vehicleType` để lấy cả part loại `BOTH`. Hạn lượng hoàn theo ISSUE có trong chi tiết phiếu. |
| `POST /staff/repair-orders/{id}/part-issues` | `{partId,quantity}` và `Idempotency-Key`. `201` trả `{movement,stockQuantity,partItem,orderVersion,repairTotal}`. |
| `POST /staff/repair-orders/{id}/part-returns` | `{originalIssueId,quantity}` và `Idempotency-Key`. `201` cùng hình dạng ISSUE. |
| `PATCH /staff/repair-orders/{id}/part-items/{itemId}/price` | `{expectedVersion,unitPrice,reason}`. `200` trả `{partItem,orderVersion,repairTotal}`. Giá và audit commit cùng nhau. Cùng giá hiện tại trả kết quả hiện có sau kiểm trạng thái/version. |
| `POST /staff/repair-orders/{id}/complete` | `{expectedVersion}`. `200` với `{repairOrderId,bookingId,status,bookingStatus,version,completedAt,invoice:{id,code,totalAmount,paymentStatus,issuedAt}}`. Nếu đã `COMPLETED`, trả hóa đơn hiện tại kể cả version gửi lại cũ. |
| `POST /admin/parts` và `POST /admin/stock-receipts` | Dùng API kho. Tạo part `{code,name,unit,vehicleType,referencePrice}` → `201 PartView`; nhập kho `{partId,quantity,reason,note?}` và `Idempotency-Key` → `201 {movement,stockQuantity}`. |

Phân trang dùng `{items,page,size,totalElements,totalPages}`, page từ `0`, size `20` mặc định và tối đa `100`. Lịch/phiếu và danh sách kho có thứ tự thời điểm giảm dần rồi ID giảm dần. SQL filter và sort dùng tập giá trị cho phép. Tách DTO ghi và DTO đọc.

Các controller Staff lấy actor từ `GaragePrincipal` và gọi use case với dữ liệu đã xác thực. Application kiểm role Staff/Admin theo use case, Admin bắt buộc cho lệnh chuẩn bị kho. Dùng `SecurityConfiguration` và `SessionValidityFilter` hiện tại. Trường actor, trạng thái, giá snapshot lần đầu xuất, tồn và tổng được server xác định.

Lỗi nghiệp vụ dùng `code`, `message` tiếng Việt, `fieldErrors` và `traceId` theo kiến trúc. `400` cho định dạng, field, dịch vụ/xe không hợp lệ; `404` cho ID không tồn tại hoặc item thuộc phiếu khác; `409 STALE_VERSION`, `BOOKING_STATE_CONFLICT`, `REPAIR_NOT_IN_PROGRESS`, `REPAIR_TOTAL_LIMIT_EXCEEDED` và `IDEMPOTENCY_CONFLICT`. Các mã lỗi PART/tồn dùng cùng [hợp đồng kho](phu-tung-ton-kho.md#3-hợp-đồng-triển-khai), gồm `PART_INACTIVE`, `PART_VEHICLE_MISMATCH`, `INSUFFICIENT_STOCK`, `STOCK_LIMIT_EXCEEDED`, `RETURN_EXCEEDS_ISSUE`. Thiếu liên kết bắt buộc hoặc ledger lệch làm rollback, log traceId và trả `500 REPAIR_DATA_INCONSISTENT` với thông báo chung. Dùng `503 REQUEST_IN_PROGRESS` kèm `Retry-After: 1` khi hết thời gian chờ key. Guest đọc API Staff trả `401`; thao tác ghi thiếu CSRF có thể trả `403` trước bước xác thực. Staff gọi API Admin trả `403` với CSRF hợp lệ.

### Thứ tự khóa và phiên bản

| Lệnh | Thứ tự trong transaction |
|---|---|
| Online check-in | Đọc `slot_id` làm gợi ý → khóa slot theo PK → khóa booking và kiểm tra lại `slot_id` → đọc/tạo phiếu. Nếu slot đổi trong lúc chờ, trả `409 STALE_VERSION`; yêu cầu tải lại. Booking đã tiếp nhận/hoàn tất trả phiếu hiện tại trước khi xét stale. |
| Walk-in | Key → customer theo unique phone → vehicle theo unique customer/plate → ghi booking + snapshots + phiếu → kết quả idempotency. Xác thực danh mục qua `catalog.application`. |
| Sửa header/LABOR/giá PART | Khóa phiếu → kiểm tra `IN_PROGRESS` và version → dòng/audit liên quan. |
| Xuất/hoàn | Key → phiếu → part → ISSUE gốc nếu hoàn. Đọc `part_id` của ISSUE gốc làm gợi ý rồi kiểm tra lại ISSUE/part/dòng sau khi khóa. Với thao tác nhiều part về sau, khóa part theo ID tăng dần. |
| Hoàn tất | Đọc `booking_id` bất biến làm gợi ý → booking → phiếu → xác nhận net ledger và tính tiền → invoice. Cả `CHECKED_IN` và `COMPLETED` tính giữ chỗ, nên lệnh hoàn tất chỉ cần khóa từ booking trở đi. |
| Nhập kho | Key → part → ledger. |

Các câu khóa dùng SQL Server `UPDLOCK, HOLDLOCK` với equality trên PK/unique thích hợp. Kiểm tra row/range locks và thứ tự với các đường ghi của lịch/Guest Booking trên JDBC thực. Mỗi thay đổi thành công đối với phiếu hoặc dòng tăng version phiếu một lần; check-in và hoàn tất tăng version booking khi chuyển trạng thái; replay và rollback giữ version. ISSUE/RETURN dùng khóa phiếu, trạng thái và điều kiện tồn dưới khóa; `expectedVersion` dùng cho các lệnh trong bảng có trường này. Giữ transaction ở application ngoài cùng, adapter dùng cùng datasource và kết nối transaction-bound. Các hợp đồng ghi kho/hóa đơn tham gia transaction đang mở.

## 4. Subtask triển khai

Tên file mới là vị trí đề xuất. Trong danh sách dưới, `B = backend/src/main/java/vn/garage`, `T = backend/src/test/java/vn/garage`, `M = backend/src/main/resources/db/migration`, `F = frontend/src`. Sửa file có sẵn theo phạm vi ghi bên dưới. Mỗi mục có thể được bàn giao và kiểm thử riêng theo phụ thuộc đã nêu.

### SC-01 — Chốt hợp đồng và ghép nhánh lịch

- **File** `docs/requirements.md` §3.4, §4–6; `docs/architecture.md` §3.3–3.5, §4.3–4.4; `docs/erd.md` §3.6–3.9; `docs/plans/repair-intake-handoff.md`.
- **Bước** Kiểm tra `git status`, `git log`, migration và thay đổi lịch đang làm. Xác nhận mặc định ở §3, đường giao với Guest Booking và thứ tự khóa dùng chung. Chốt tên/mốc migration và người thực hiện hạng mục chung với INV-01, gồm schema, idempotency, audit, DTO PART và UI tích hợp. Đồng bộ tài liệu hợp đồng trong thay đổi thực hiện feature.
- **Kiểm tra và đạt khi** Bảng quyền/HTTP, nullable, field length, loại dòng, version và trạng thái nhất quán giữa ba tài liệu; có kịch bản và chủ sở hữu cho tích hợp Guest Booking. Phụ thuộc mốc lịch/catalog có thể build với V004.

### SC-02 — Migration phiếu, kho, hóa đơn và audit

- **File** Dùng bộ của INV-02 gồm `M/V005__create_parts.sql`, `M/V006__create_repair_orders_and_items.sql`, `M/V007__create_stock_movements_and_audit_logs.sql`. Thêm `M/V008__create_invoices.sql`, cập nhật `M/README.md`, `T/migration/FoundationMigrationIT.java`, `backend/README.md`. V005–V008 là số dự kiến khi V004 vẫn là version mới nhất.
- **Bước** INV-02 chuẩn bị `parts` (có version), `repair_orders`, `repair_items`, `stock_movements` và `audit_logs`. SC-02 kiểm lại kiểu, `DEFAULT`, FK `NO ACTION`, CHECK và index ERD §3.6–4, gồm `UNIQUE(repair_orders.booking_id)` và `UX_repair_items_order_part` có filter. Thêm `voided_*` theo SC-01 trong V006 nếu migration chưa được áp dụng. Nếu đã áp dụng, cấp version mới cho phần bổ sung. Hai trường void cùng null hoặc cùng có giá trị, chỉ áp dụng LABOR. V008 tạo `invoices`, `UNIQUE(invoices.repair_order_id)` và ràng buộc payment fields; dùng `IS NOT NULL` rõ ràng cho các nhánh CHECK có NULL, đặc biệt bộ trường PAID. Schema cộng thêm giữ dữ liệu lịch sử, chưa cần backfill phiếu cho booking `CONFIRMED`.
- **Test** Mở rộng metadata, bảng cho phép và count lịch sử của `FoundationMigrationIT`; chạy sạch, chạy lại `0` migration và nâng cấp V004 → V008 trên DB test khác có fixture lịch/booking `CONFIRMED`. Guard kiểm tra `DB_NAME()`, tập bảng và lịch sử migration nhận đúng schema V004, schema chung V007 hoặc schema đích. Kiểm tra FK/unique, PART trùng, LABOR/void sai tổ hợp, state/completed fields sai, tồn âm, ledger sai dấu/liên kết, sai PAID/null và JSON audit lỗi.
- **Đạt khi** Tất cả cấu hình SQL Server thực qua `mvn -Psqlserver-it verify`; booking/slot/customer/snapshot từ V004 còn nguyên sau nâng cấp; schema không có dữ liệu mẫu. Phụ thuộc SC-01.

### SC-03 — Idempotency, kết nối ghi và audit

- **File** Dùng bộ của INV-04 gồm `B/support/idempotency/IdempotencyExecutor.java`, `B/support/idempotency/JdbcIdempotencyStore.java`, `B/support/idempotency/IdempotencyCleanupJob.java`, `B/support/transaction/SqlServerWriteTransactions.java`, `T/support/idempotency/IdempotencyExecutorTest.java`, `T/support/idempotency/IdempotencySqlServerIT.java`. Dùng chung audit với INV-06 ở `B/support/audit/AuditWriter.java`, `B/support/audit/JdbcAuditWriter.java`. Chỉnh `B/repair/api/RepairErrors.java` và `backend/src/main/resources/application.yml`.
- **Bước** Thiết lập SET cho connection ghi của pool theo V002 và `FoundationMigrationIT`, kiểm tra `LOCK_TIMEOUT` 5 giây trên connection thật và khôi phục trước khi trả pool. Scope gồm use case + actor ID khi nội bộ. Fingerprint SHA-256 bao gồm target ID và các field canonical có nghĩa, gồm cả snapshot sẽ lưu, lượng chuẩn hóa và mục tiêu return. Key case-sensitive ASCII theo `[A-Za-z0-9._-]{1,100}`. Khóa composite PK trước lệnh, bind đúng kiểu VARCHAR để dùng index. Lưu HTTP status và response tối thiểu của giao dịch thành công, giữ ít nhất 24 giờ. Khi gặp key đã hết hạn, thay kết quả dưới khóa nếu lệnh mới commit. Dọn theo lô qua index expiry. Ánh xạ SQL Server `1222` sang `503` cho lệnh dùng key sau rollback; deadlock `1205` retry tối đa hai lần ở ngoài transaction cũ, giữ nguyên key. Audit lấy actor từ principal, reason và snapshot giá tối thiểu trong cùng transaction. Ở advice của repair, dùng exception feature và scope/thứ tự phù hợp để `IdentityExceptionHandler` không trả `USERNAME_EXISTS` cho lỗi constraint của phiếu/kho.
- **Test** Cùng key/cùng canonical payload replay; cùng key khác target/body conflict; khác actor hoặc khác hoa/thường độc lập. Hai connection kiểm tra chờ commit, chờ rollback rồi chạy tiếp và timeout với `Retry-After`. Test job chỉ dọn bản hết hạn và cùng key vẫn chạy đúng sau cleanup. Kiểm tra filtered index bằng kết nối ghi ứng dụng, mapping SQL lỗi, rollback audit và version.
- **Đạt khi** Một thao tác có đúng một kết quả commit và một tập ghi nghiệp vụ, không có pending row commit riêng; hết thời gian chờ có thể retry cùng key. Phụ thuộc SC-02 cho audit; phần dùng `idempotency_records` có thể bắt đầu từ V002.

### SC-04 — Dùng lại khách/xe và dịch vụ cho tiếp nhận

- **File** `B/customer/domain/ContactAndVehicle.java`, `B/customer/domain/CustomerVehicleStore.java`, `B/customer/application/CustomerVehicleOnboarding.java`, `B/customer/infrastructure/JdbcCustomerVehicleStore.java`, `B/catalog/application/ServiceCatalog.java`, `B/catalog/domain/BookingServiceLookup.java`, `B/catalog/infrastructure/JdbcBookingServiceLookup.java`; `T/customer/domain/ContactAndVehicleTest.java`, `T/customer/infrastructure/CustomerVehicleOnboardingIT.java`, `T/catalog/infrastructure/BookingServiceLookupIT.java`.
- **Bước** Kiểm tra họ tên 2–100, SĐT di động VN dạng `0…`/`+84…` về `+84…`, biển số hiển thị tối đa 20 và chuẩn hóa hoa, bỏ khoảng trắng, `.` và `-`, còn A–Z/0–9. `brand`/`model` tùy chọn tối đa 100, `note` đặt lịch tối đa 1.000. Tìm/tạo dưới khóa unique phone, rồi customer + plate; kiểm tra loại xe khi tái dùng, giữ hồ sơ cũ, trả ID và dữ liệu snapshot để `repair` ghi trong cùng transaction. Qua catalog contract đọc theo tập ID, bắt buộc ít nhất một, không lặp, đang active và tương thích loại xe hoặc `BOTH` cho walk-in; mở cùng hợp đồng cho Guest Booking. LABOR có link thì kiểm tra dịch vụ phù hợp qua hợp đồng catalog, công việc phát sinh dùng ID null.
- **Test** Chuẩn hóa và giá trị biên, hồ sơ mới/cũ, xe cùng biển khác customer, xe cùng customer sai loại, đồng thời một số điện thoại chỉ tạo một hồ sơ/xe. Kiểm tra loại xe, service trùng, inactive và snapshot giá; rollback hồ sơ khi caller lỗi.
- **Đạt khi** Walk-in và Guest Booking dùng cùng quy tắc hồ sơ; truy vấn nằm trong infrastructure đúng module. Phụ thuộc SC-01 và schema nền.

### SC-05 — Danh sách lịch và check-in online

- **File** `B/repair/domain/RepairStatus.java`, `B/repair/domain/BookingStore.java`, `B/repair/domain/RepairOrderStore.java`, `B/repair/application/VehicleIntakeService.java`, `B/repair/application/StaffBookingQuery.java`, `B/repair/infrastructure/JdbcBookingStore.java`, `B/repair/infrastructure/JdbcRepairOrderStore.java`, `B/repair/api/StaffBookingsController.java`, `B/repair/api/RepairErrors.java`; `T/repair/api/StaffBookingsApiTest.java`, `T/repair/infrastructure/OnlineCheckInIT.java`.
- **Bước** Tách query danh sách theo ngày địa phương và projection dịch vụ đã đặt. Trong check-in áp dụng thứ tự khóa ở §3. Lấy actor ID từ `GaragePrincipal` tại API, ghi `receivedBy/receivedAt` bằng `Clock` hiện có, tạo đúng một phiếu `IN_PROGRESS`, cập nhật booking `CHECKED_IN`. Dữ liệu tiếp nhận dùng số km nguyên không âm trong phạm vi `INT`, hai ghi chú tùy chọn tối đa 2.000 ký tự. Bỏ qua kiểm tra lead time/capacity khi tiếp nhận lịch đã xác nhận vì chỗ đã được giữ. Hai trạng thái đã tiếp nhận dùng đường replay theo trạng thái và FK; lỗi invariant nếu thiếu phiếu.
- **Test** Fixture `CONFIRMED`, `CANCELLED`, `NO_SHOW`; lịch đã đóng và lịch quá giờ; stale khi slot thay đổi; double-click/hai phiên; tiêm lỗi lúc tạo phiếu để chứng minh booking giữ `CONFIRMED`. Assert người nhận, snapshot và capacity trước/sau. Kiểm tra biên ngày giờ, phân trang ổn định và `repairOrderId` sau tiếp nhận.
- **Đạt khi** Hai lần check-in trả cùng ID, chỉ một phiếu tồn tại và chỗ vẫn được tính; trạng thái sai bị từ chối, lịch đúng mốc ngày hiển thị. Phụ thuộc SC-02 và hợp đồng lịch ở SC-01.

### SC-06 — Walk-in nguyên tử

- **File** `B/repair/application/VehicleIntakeService.java`, `B/repair/infrastructure/JdbcBookingStore.java`, `B/repair/infrastructure/JdbcRepairOrderStore.java`, `B/repair/api/StaffBookingsController.java`; `T/repair/api/StaffBookingsApiTest.java`, `T/repair/infrastructure/WalkInIntakeIT.java`.
- **Bước** Dùng SC-03 trước mọi ghi, xác thực qua SC-04, tạo mã `BK-` + 12 ký tự uppercase ngẫu nhiên theo ERD. Nếu trùng unique code, rollback rồi thử sinh lại có giới hạn trong transaction mới, phân biệt lỗi này với lỗi khóa khác. Tạo booking `WALK_IN/CHECKED_IN` với `created_by` từ session, `slot_id` null, contact/vehicle/service snapshots, rồi phiếu `IN_PROGRESS` theo giờ thực. Commit response `201` tối thiểu trong idempotency; dùng record đã lưu khi retry.
- **Test** Hồ sơ mới và tái dùng `0…`/`+84…`, hai xe theo cùng hồ sơ, dịch vụ sai, thiếu dữ liệu, double submit và response bị mất sau commit. Giả lập lỗi giữa insert booking/phiếu và kiểm DB rollback hồ sơ mới, booking, phiếu và kết quả key. Đếm lượt slot trước/sau không đổi.
- **Đạt khi** Một thao tác walk-in có một booking và một phiếu; retry nhận cùng ID; staff mở trực tiếp phiếu để ghi công. Phụ thuộc SC-03, SC-04 và SC-05.

### SC-07 — Phiếu sửa, dòng LABOR và tính tiền

- **File** `B/repair/domain/RepairTotals.java`, `B/repair/domain/RepairLaborRepository.java`, `B/repair/application/RepairOrderService.java`, `B/repair/application/RepairOrderQuery.java`, `B/repair/infrastructure/JdbcRepairOrderStore.java`, `B/repair/infrastructure/JdbcRepairLaborRepository.java`, `B/repair/api/RepairOrdersController.java`, `B/repair/api/RepairErrors.java`; `T/repair/domain/RepairTotalsTest.java`, `T/repair/api/RepairOrdersApiTest.java`, `T/repair/infrastructure/RepairLaborIT.java`.
- **Bước** GET list/detail chỉ đọc. Tách booking services, LABOR, PART, nhận xe và ghi chú nội bộ. PATCH header phân biệt field vắng/null. LABOR mô tả văn bản thuần 1–200, số lượng `>0` và tối đa ba chữ số thập phân, đơn giá nguyên không âm, `serviceId` tùy chọn. Thêm/sửa/bỏ dưới khóa phiếu và kiểm version; `DELETE` thực hiện theo quyết định SC-01. Chặn dùng endpoint LABOR với item PART hoặc khác phiếu. Tính `quantity.multiply(unitPrice).setScale(0, HALF_UP)` từng dòng rồi cộng, bỏ PART có net `0` và LABOR đã bỏ; duy trì `DECIMAL(18,3)`/`DECIMAL(18,0)` và giới hạn tiền `9007199254740991` ở giá, thành tiền và tổng. Mọi lệnh làm tổng vượt ngưỡng rollback trước commit.
- **Test** `0.500 × 101` thành `51`; hai dòng như vậy thành `102`; compare SQL `ROUND`, giá 0, scale 4, qty âm/0 và tiền vượt giới hạn. Kiểm thử stale, sửa nhầm item/phiếu, bỏ dòng, khóa khi hoàn tất và snapshot booking services không đổi khi thêm công việc phát sinh hoặc khi đổi giá catalog.
- **Đạt khi** Chi tiết cho thấy phí dự kiến theo công việc thực tế; dịch vụ yêu cầu ban đầu giữ nguyên; phiên sửa cũ nhận `409`. Phụ thuộc SC-04 và SC-05.

### SC-08 — Chuẩn bị danh mục và tồn kho

- **File** Dùng INV-03/05 tại `B/inventory/domain/StockQuantity.java`, `B/inventory/application/PartCatalog.java`, `B/inventory/application/StockCommands.java`, `B/inventory/infrastructure/JdbcPartRepository.java`, `B/inventory/infrastructure/JdbcStockMovementRepository.java`, `B/inventory/api/PartController.java`, `B/inventory/api/AdminStockController.java`; `T/inventory/api/InventoryApiTest.java`, `T/inventory/InventorySqlServerIT.java`.
- **Bước** Staff/Admin đọc part và tồn theo loại xe/trạng thái. Admin tạo part, code unique, tên tối đa 150, đơn vị tối đa 30, giá hợp lệ và tồn `0`. Admin nhập tồn dương qua khóa key/part, ghi movement `RECEIPT` có lý do trim tối đa 500, ghi chú tùy chọn theo schema và actor từ session. Đồng thời cập nhật tồn, version part và ghi ledger. Kiểm giới hạn `999999999999999.999` trước ghi. Nếu phần kho đã tích hợp, chạy smoke trên các API này và lấy bằng chứng của INV-03/05.
- **Test** Role, CSRF, tồn mặc định, trùng mã, nhập số lẻ, retry không cộng đôi, receipt cạnh tranh giữ đúng số trước/sau, rollback khi ghi ledger lỗi. Chuẩn bị dữ liệu tổng hợp qua API Admin khi chạy E2E.
- **Đạt khi** Có đường vận hành hợp lệ để nạp vật tư trước khi xuất, không cần cập nhật trực tiếp cột tồn. Phụ thuộc SC-02, SC-03.

### SC-09 — Xuất kho theo phiếu

- **File** Dùng INV-06 tại `B/repair/application/RepairPartCommands.java`, `B/repair/domain/RepairPartRepository.java`, `B/repair/infrastructure/JdbcRepairPartRepository.java`, `B/repair/api/RepairPartsController.java`, `B/inventory/application/RepairStockOperations.java`, `B/inventory/infrastructure/JdbcPartRepository.java`, `B/inventory/infrastructure/JdbcStockMovementRepository.java`; `T/repair/RepairStockSqlServerIT.java`, `T/repair/api/RepairPartsApiTest.java`.
- **Bước** Repair khóa phiếu/kiểm trạng thái, gọi `RepairStockOperations` khóa part và trả thông tin đã khóa trước khi insert dòng có FK part. Kiểm active, tương thích `vehicle_type_snapshot`, lượng dương ≤ 3 số lẻ, đủ tồn; lần đầu snapshot mô tả, đơn vị, giá; lần sau dùng dòng hiện có và giá của dòng. Gọi inventory ghi ISSUE với actor/lý do và trừ tồn theo ID dòng; tăng net quantity/version phiếu, version part và lưu response trong transaction. Inventory chỉ nhận ID tham chiếu đã được repair kiểm tra. Chặn quantity và tổng tiền vượt giới hạn trước commit.
- **Test** Hai phiếu tranh lượng cuối; cùng part hai lần trên một phiếu; catalog đổi giá qua API Admin của kho hoặc setup fixture nếu chưa có PATCH; inactive/sai xe/thiếu tồn; cùng key replay. Tiêm lỗi sau khi inventory đã ghi để xác minh rollback tồn, ledger, item, version và key.
- **Đạt khi** Tồn không âm, một dòng PART mỗi phụ tùng, một ledger mỗi lần xuất commit, giá lần đầu được giữ. Phụ thuộc SC-07, SC-08.

### SC-10 — Hoàn kho theo lần xuất và đổi giá PART

- **File** Dùng INV-06 tại `B/repair/application/RepairPartCommands.java`, `B/repair/application/RepairPartQuery.java`, `B/repair/application/RepairOrderQuery.java`, `B/repair/api/RepairPartsController.java`, `B/inventory/application/RepairStockOperations.java`, `B/inventory/infrastructure/JdbcStockMovementRepository.java`, `B/support/audit/AuditWriter.java`; `T/repair/RepairStockSqlServerIT.java`, `T/repair/api/RepairPartsApiTest.java`.
- **Bước** Xác minh ISSUE gốc thuộc phiếu và đúng part/dòng; tính lượng còn hoàn theo tổng RETURN trỏ cùng ISSUE dưới khóa part và phiếu. Tăng tồn, ghi RETURN dương với actor/lý do, giảm net và tăng version part/phiếu; vẫn cho hoàn part inactive. Giữ dòng/giá khi quantity chạm `0`. Query chi tiết phiếu lấy số còn được hoàn theo ISSUE qua hợp đồng inventory. PATCH giá riêng kiểm version, `IN_PROGRESS`, loại PART và lý do 1–500 ký tự. Giá mới áp dụng toàn dòng, giữ số lượng/tồn/ledger. Chỉ ghi audit và tăng version khi giá thay đổi. Audit dùng `entity_type=REPAIR_ITEM`, `action=REPAIR_PART_PRICE_UPDATED`; lưu actor, giá/tổng trước sau, quantity và lý do.
- **Test** Hoàn một phần/toàn phần, hoàn đồng thời không vượt ISSUE gốc, khác phiếu/khác loại movement, replay và hoàn inactive. Hoàn hết rồi xuất lại dùng đúng item và giá hiện tại. Sửa giá ảnh hưởng toàn lượng ròng, thiếu lý do/stale/lỗi audit rollback; assert movement gốc còn nguyên.
- **Đạt khi** Mỗi RETURN truy về ISSUE thật, tồn và net khớp, có một audit cho mỗi lần đổi giá commit, không thể đổi trực tiếp lượng PART. Phụ thuộc SC-09.

### SC-11 — Chốt phiếu và tạo hóa đơn

- **File** `B/repair/application/RepairCompletionService.java`, `B/repair/application/RepairOrderQuery.java`, `B/repair/api/RepairOrdersController.java`, `B/billing/application/InvoiceIssuance.java`, `B/billing/domain/InvoiceStore.java`, `B/billing/infrastructure/JdbcInvoiceStore.java`, `B/inventory/application/RepairStockOperations.java`; `T/repair/infrastructure/RepairCompletionIT.java`, `T/repair/infrastructure/RepairConcurrencyIT.java`, `T/repair/api/RepairOrdersApiTest.java`.
- **Bước** Áp dụng thứ tự booking → phiếu. Dưới khóa phiếu lấy net theo item từ hợp đồng inventory, đối chiếu với từng dòng PART và chặn khi lệch. Tính lại tiền từ dòng hiệu lực. Cập nhật phiếu `COMPLETED`, `completed_by/at`, booking `COMPLETED`, gọi `InvoiceIssuance` trong cùng transaction để ghi mã `INV-` + 12 ký tự uppercase, tổng tiền cố định, `UNPAID` và các trường paid null. Retry va chạm mã qua transaction mới có giới hạn. Nếu phiếu đã hoàn tất thì lấy hóa đơn theo unique FK để trả lại kết quả hiện tại. `RepairOrderQuery` đọc invoice summary qua hợp đồng billing.
- **Test** Tổng 0 và tổng có lẻ `.5`, tạo đúng một invoice ở hai completion đồng thời, thay giá danh mục sau chốt không đổi tổng. Giả lập invoice insert lỗi sau khi cập nhật phiếu/booking để chứng minh rollback. Dùng barrier và nhiều kết nối cho hai thứ tự check-in lặp × hoàn tất, xuất/hoàn × hoàn tất và LABOR × hoàn tất. Assert không chuyển lùi, không ghi sau chốt, giữ lượt slot, không có deadlock do đảo thứ tự booking/phiếu. Test phát hiện ledger lệch và từ chối tại thời điểm chốt.
- **Đạt khi** Chỉ có hai kết quả hợp lệ khi đua với ghi phiếu: lệnh ghi commit trước và được tính vào tổng, hoặc hoàn tất trước và lệnh ghi bị từ chối. Một hóa đơn cố định cho mỗi phiếu hoàn tất. Phụ thuộc SC-05, SC-07, SC-10.

### SC-12 — Giao diện tiếp nhận

- **File** `F/app/App.tsx`, `F/app/app.css`, `F/api/client.ts`, `F/features/booking/intakeApi.ts`, `F/features/booking/IntakePage.tsx`, `F/features/booking/WalkInForm.tsx`.
- **Bước** Gắn `/staff/bookings`, `/staff/walk-ins` vào History API hiện tại, bổ sung liên kết cho cả Staff/Admin và giữ luồng đăng nhập/đăng xuất. Lịch có bộ lọc ngày/trạng thái và nút tiếp nhận chỉ khi `CONFIRMED`; mở phiếu cũ khi đã tiếp nhận. Form walk-in lấy service đang active theo loại xe từ API hiện có; khi đổi loại xe tải lại và xóa lựa chọn service không còn hợp lệ. Submit điều hướng đến `/staff/repair-orders/{id}`. Giữ input khi validation/stale; khi stale tải lại và yêu cầu đối chiếu. Mở rộng `ApiError` đọc `fieldErrors` và `Retry-After`, hỗ trợ HTTP 204. Với walk-in, sinh UUID trước khi gửi, vô hiệu submit trong khi gửi, giữ key/payload khi timeout/mất phản hồi và chỉ tạo key khi bắt đầu ý định mới. Hiển thị rõ thao tác chưa xác nhận và cho gửi lại cùng key.
- **Test** `npm run build`; E2E SC-14 cho lọc ngày, tiếp nhận lịch đóng, walk-in, double-click, mất phản hồi, đăng nhập lại và mở route trực tiếp. Bao phủ loading/empty/error; quay lại bằng nút Back và menu.
- **Đạt khi** Nhân viên chọn lịch hoặc nhập walk-in và tới đúng một phiếu; form thông báo lỗi đúng field và giữ dữ liệu cần nhập lại. Phụ thuộc SC-05–06 và hợp đồng chi tiết SC-07.

### SC-13 — Giao diện sửa chữa và hoàn tất

- **File** `F/app/App.tsx`, `F/app/app.css`, `F/features/repair/repairApi.ts`, `F/features/repair/RepairOrdersPage.tsx`, `F/features/repair/RepairOrderDetailPage.tsx`, `F/features/repair/LaborItemsForm.tsx`. Gắn panel của INV-09 tại `F/features/repair/RepairPartsPanel.tsx`, dùng `F/features/repair/partApi.ts` và `F/features/inventory/useStockCommand.ts`.
- **Bước** Gắn list/detail với bộ lọc trạng thái, thông tin xe, hồ sơ tiếp nhận và hai vùng dịch vụ yêu cầu/công việc thực tế. Dùng tổng backend làm nguồn hiển thị. Giữ giá và số lượng ở dạng input thập phân, validate scale/phạm vi trước gửi. Tách xác nhận ISSUE, RETURN theo ISSUE gốc và đổi giá có lý do; hiển thị tồn, lượng còn hoàn và tổng mới. Với ISSUE/RETURN dùng retry key như walk-in. Gửi version cho lệnh yêu cầu, tải lại khi `STALE_VERSION`; sau replay tải chi tiết hiện tại để tránh dựng màn hình từ response cũ. Hiển thị hộp xác nhận hoàn tất, sau thành công chỉ hiển thị thông tin phiếu và mã/tổng hóa đơn. Bỏ qua response đọc cũ khi đổi route hoặc tải nhanh liên tiếp.
- **Test** `npm run build`; E2E SC-14 cho ghi công, xuất/hoàn/đổi giá, tồn thiếu, stale hai tab và hoàn tất. Kiểm tra chi tiết khi refresh, `401` quay về login, `403` và lỗi CSRF hiển thị phù hợp, biểu mẫu có label và thao tác được bằng bàn phím.
- **Đạt khi** Từ danh sách phiếu, Staff/Admin xử lý đủ nghiệp vụ SC-07–11. Phiếu hoàn tất mở lại ở chế độ chỉ đọc, hiển thị đúng trạng thái và mã hóa đơn. Phụ thuộc SC-07–12.

### SC-14 — Nghiệm thu và cập nhật bàn giao

- **File** `T/repair/api/RepairSecurityTest.java`, `T/repair/infrastructure/RepairConcurrencyIT.java`, `frontend/e2e/repair-intake.spec.ts`, `frontend/playwright.config.ts`, `frontend/package.json`, `frontend/package-lock.json`, `frontend/README.md`, `docs/plans/repair-intake-handoff.md`.
- **Bước** Thêm E2E với Playwright và script `npm run test:e2e`. Dùng backend thật với SQL Server test riêng, fixture tổng hợp, hai tài khoản Staff/Admin và Vite proxy. Kiểm quyền qua SecurityFilterChain thật: Guest, Staff, Admin, CSRF và phiên bị thu hồi; thử gọi API trực tiếp sau khi UI ẩn nút. Với concurrency dùng transaction/connection độc lập, barrier có thời hạn, kiểm DB sau commit. Fixture dọn riêng theo scope; dùng DB browser riêng với bộ migration contract kiểm seed rỗng. Cập nhật README về lệnh, fixture và bằng chứng thực chạy.
- **Lệnh** Từ `backend`: `mvn test`, rồi `mvn -Psqlserver-it verify` với `MIGRATION_TEST_URL` và credentials test theo [backend README](../../backend/README.md). Từ `frontend`: `npm run build`, rồi `npm run test:e2e` với backend và DB test đang chạy. Ghi riêng kết quả unit/API, migration SQL Server, transaction/concurrency và browser. Với hồ sơ fixture, ghi rõ đầu vào online được tạo bằng SQL hay Guest Booking API.
- **Đạt khi** Các hàng bắt buộc trong §5 có bằng chứng tương ứng. Kịch bản online qua API Guest Booking thật còn chờ thì ghi rõ phần thiếu và giữ trạng thái nghiệm thu tích hợp đang chờ. Backup/restore là bằng chứng vận hành riêng thuộc AC-16. Phụ thuộc SC-02–13 và Guest Booking cho nhánh E2E online đầy đủ.

## 5. Ma trận nghiệm thu

| Đặc tả | Kịch bản cần ghi kết quả | Nơi kiểm |
|---|---|---|
| AC-08, AC-19 | Online và walk-in tạo thẳng `IN_PROGRESS`; retry vẫn một phiếu; guest không sửa được, phiếu đã chốt khóa mọi lệnh ghi. | `OnlineCheckInIT`, `WalkInIntakeIT`, `RepairSecurityTest`, browser |
| AC-09 | Thêm LABOR phát sinh, sửa/bỏ dòng và thay giá danh mục; snapshot dịch vụ booking vẫn giữ. | `RepairLaborIT`, browser |
| AC-10, AC-11, AC-18 | Hai phiếu tranh tồn; hai lần hoàn cạnh tranh; replay cùng key; nhiều lần xuất giữ một item và giá; hoàn hết/xuất lại; lý do và audit khi đổi giá. | `RepairStockSqlServerIT`, browser |
| AC-12, AC-23 | Hai request hoàn tất sinh một invoice, rollback khi tạo invoice lỗi, tổng 0, làm tròn `.5` theo từng dòng, tổng giữ nguyên sau đổi giá. | `RepairTotalsTest`, `RepairCompletionIT`, browser |
| AC-15, AC-21 phần hồi quy | Guest bị chặn API nội bộ, Staff không tạo part/nhập kho, Admin thao tác được, ghi thiếu CSRF bị chặn, session bị thu hồi nhận `401`. | `RepairSecurityTest` với filter chain, browser |
| AC-16 phần migration | Migrate sạch, chạy lại 0, nâng từ V004 có dữ liệu và kiểm metadata/constraints. | `FoundationMigrationIT`, SQL Server riêng |
| AC-17 phần giữ lượt | Check-in và hoàn tất online giữ occupied count như trước; walk-in không gắn slot. | `OnlineCheckInIT`, `WalkInIntakeIT`, `RepairCompletionIT` |
| AC-20 | Key mới cạnh tranh khi commit/rollback; cùng key khác payload conflict; key timeout có `Retry-After`; retry UI giữ key. | `IdempotencySqlServerIT`, các IT use case, browser mô phỏng mất phản hồi |
| AC-24 | Check-in lặp và hoàn tất đua theo cả hai thứ tự; một phiếu, một invoice, trạng thái không lùi. | `RepairConcurrencyIT`, SQL Server riêng |

Browser tối thiểu có hai đường chạy: **online** (booking thật từ API Guest Booking → danh sách Staff → check-in → LABOR → hoàn tất) và **walk-in** (Admin chuẩn bị part/tồn → Staff tiếp nhận → ghi công → xuất và hoàn một phần → đổi giá có lý do → chốt). Xác nhận DB cuối ca theo ID từ kết quả, gồm booking, phiếu, dòng PART, đối chiếu ledger, một audit giá và một invoice. Test trạng thái giao diện có thể dùng giả lập mất phản hồi sau khi POST thực đã commit. Ghi riêng E2E dùng fixture lúc chờ Guest Booking.

Thứ tự thực hiện **SC-01 → SC-02 → SC-03**, với SC-04 có thể làm cùng phần schema/idempotency. **SC-05 → SC-06**, **SC-07** sau hợp đồng phiếu, **SC-08** dùng kết quả danh mục và nhập kho từ INV-03/05. **SC-09 → SC-10 → SC-11**. SC-12 và SC-13 nối UI theo tiến độ các API. **SC-14** chốt bằng chứng và cập nhật bàn giao.
