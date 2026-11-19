# Kế hoạch Đặt lịch và tra cứu công khai

Ngày 18/11/2026 · Trạng thái triển khai trên branch `codex/guest-booking-public-lookup` · Nền: commit `b61dd5d`.

## Mục tiêu và phạm vi

Khách không cần đăng nhập có thể xem dịch vụ/khung giờ, đặt lịch sửa xe, nhận mã đặt lịch và tra cứu lịch sử theo số điện thoại hoặc mã đặt lịch. Hồ sơ `customers` và `vehicles` được lưu và tái sử dụng; dữ liệu của lần đặt được snapshot vào booking. Nhân viên/Admin tiếp tục dùng authentication hiện có.

Bao gồm backend, SQL Server transaction/concurrency, API công khai, UI React và kiểm chứng. Không gồm OTP, customer account, sửa/hủy/đổi lịch, check-in, walk-in, phiếu sửa, kho, hóa đơn, gửi SMS/email, lưu hồ sơ xe có chỉnh sửa và production deployment.

## Quyết định API

- `GET /api/public/services?vehicleType=CAR|MOTORBIKE` và `GET /api/public/slots?date=YYYY-MM-DD&vehicleType=...` là API có sẵn; chỉ trả dịch vụ đang hoạt động và slot mở, còn chỗ, đủ lead time.
- `POST /api/public/bookings`, header `Idempotency-Key` là UUID. Input gồm `contact:{fullName,phone}`, `vehicle:{type,plateNumber,brand?,model?}`, `serviceIds:string[]`, `slotId:string`, `note?`. Trả `201` với `bookingCode,status,startsAt,endsAt,vehicleType,services:[{name,referencePrice}],referenceTotal`; booking code dạng `BK-` cộng 12 ký tự ngẫu nhiên; không trả ID hồ sơ nội bộ.
- `POST /api/public/lookups` nhận đúng một trong `phone` hoặc `bookingCode`, cùng `page` (0-based, mặc định 0) và `size` (mặc định 20, tối đa 100). Trả `{items,page,size,totalElements,totalPages}`. Mã đặt lịch trả tối đa một kết quả; số điện thoại trả các booking của đúng số đã chuẩn hóa, mới nhất trước.
- Lookup trả code, thời gian tạo, slot nullable, loại xe, biển số che trên server, trạng thái, dịch vụ snapshot và tổng giá tham khảo. Không trả tên/số điện thoại đầy đủ, ghi chú, customer/vehicle ID, thông tin sửa chữa hoặc hóa đơn. Không có kết quả trả page rỗng. Tất cả response lookup có `Cache-Control: no-store`; không log dữ liệu request.
- Số điện thoại Việt Nam kiểm tra hợp lệ và lưu E.164 `+84...`; tên trim, 2–100 ký tự. Biển số chuẩn hóa bỏ dấu phân cách và uppercase ASCII. Dịch vụ 1–50 ID khác nhau, đang hoạt động, tương thích xe. ID BIGINT là chuỗi JSON; tiền VND là số nguyên an toàn của JavaScript.
- Cùng idempotency key và payload trong 24 giờ trả nguyên status/body đã commit; cùng key khác payload trả `409 IDEMPOTENCY_CONFLICT`. Booking và record idempotency cùng transaction.
- Lỗi: `400` dữ liệu/dịch vụ không hợp lệ; `404` booking code không tồn tại; `409 BOOKING_CONFLICT|SLOT_FULL|SLOT_UNAVAILABLE|IDEMPOTENCY_CONFLICT`; `429` rate limit; `503 SLOT_SCHEDULE_UNAVAILABLE|REQUEST_IN_PROGRESS` hoặc lỗi phụ thuộc SQL Server.
- Đặt lịch theo múi giờ `Asia/Ho_Chi_Minh`; không chấp nhận slot đã bắt đầu, ngoài horizon, dưới minimum lead, đóng hoặc hết chỗ. Thứ tự khóa: idempotency key → garage_settings → slot → service snapshot → customer → vehicle. Kiểm tra biển số đang chiếm cùng slot trước capacity.
- Tái sử dụng customer theo unique `phone_normalized` có khóa update-range. Không ghi đè tên hồ sơ cũ. Tái sử dụng xe theo `(customer_id, plate_normalized)`; xe cùng biển số nhưng khác loại là conflict, không âm thầm đổi loại.
- Giới hạn theo IP: tạo booking 10 request/10 phút, lookup 30 request/phút, tối đa 4096 bucket mỗi bộ đếm. Chỉ tin IP forwarding từ proxy được cấu hình rõ.
- Public routes không cần session, bỏ CSRF đúng hai POST public đã nêu; API nội bộ vẫn giữ phân quyền/CSRF. Public frontend không gửi cookie phiên.

## Subtask triển khai

`J=backend/src/main/java/vn/garage`, `T=backend/src/test/java/vn/garage`, `F=frontend/src`.

### GB-01 — Chuẩn hóa dữ liệu guest

- **Files:** `J/customer/domain/CustomerContactRules.java`, `J/repair/domain/GuestBookingRequest.java`, `backend/pom.xml`; tests tương ứng dưới `T/customer/domain/` và `T/repair/domain/`.
- **Steps:** thêm libphonenumber phiên bản `9.0.43`; chuẩn hóa/kiểm tra VN mobile; kiểm tra tên, plate, loại xe, service ID, note và giới hạn; tạo immutable request/domain values.
- **Tests:** số dạng `0...`/`+84...`, số invalid, unicode, khoảng trắng, plate separator/case, type mismatch và giới hạn danh sách.
- **Acceptance:** request được chuẩn hóa duy nhất trước khi hashing/tìm customer; input sai có mã lỗi ổn định, không ghi DB.

### GB-02 — Tìm/tạo hồ sơ Customer và Vehicle

- **Files:** dùng `J/customer/domain/ContactAndVehicle.java`, `J/customer/domain/CustomerVehicleStore.java`, `J/customer/application/CustomerVehicleOnboarding.java`, `J/customer/infrastructure/JdbcCustomerVehicleStore.java` do SC-04 sở hữu. Các contract và adapter tìm/tạo này phục vụ cả Guest Booking và walk-in. Test thuộc SC-04 với case public booking.
- **Steps:** gọi `CustomerVehicleOnboarding` trong transaction booking đang mở. Tìm/tạo theo equality unique phone với `UPDLOCK,HOLDLOCK`; giữ tên hồ sơ cũ; upsert xe theo customer/plate; từ chối sai vehicle type. Guest Booking không tạo repository hoặc service upsert riêng.
- **Tests:** customer/vehicle đã có, tạo mới, cùng SĐT đồng thời, cùng biển số, không ghi đè, rollback.
- **Acceptance:** không phát sinh hồ sơ trùng dưới cạnh tranh SQL Server; booking lỗi rollback cả hồ sơ mới.

### GB-03 — Hợp đồng dịch vụ và kiểm tra slot khi ghi

- **Files:** `J/catalog/application/BookingServiceCatalog.java`, `J/catalog/infrastructure/JdbcBookingServiceCatalog.java`, `J/repair/domain/BookingSlotPolicy.java`, `J/repair/infrastructure/JdbcBookingSlotRepository.java`, chỉnh `SlotGenerator.java`; tests unit/integration tương ứng.
- **Steps:** lấy snapshot dịch vụ đang hoạt động theo ID tăng dần; kiểm tra loại xe; khóa settings trước slot; khóa slot chính xác bằng PK `UPDLOCK,HOLDLOCK`; kiểm tra lead/horizon/closed/capacity/plate; đảm bảo mọi schedule writer giữ khóa settings trước slot.
- **Tests:** dịch vụ ngừng/sai loại, slot hết/đóng/đã qua/ngoài lead, biển số đang được giữ, hai writer tranh slot và race đổi lịch.
- **Acceptance:** mọi kiểm tra booking cuối cùng chạy dưới SQL Server transaction/lock; lỗi không để lại thay đổi dở.

### GB-04 — Transaction tạo booking và idempotency

- **Files:** `J/repair/application/GuestBookingService.java`, `J/repair/infrastructure/JdbcGuestBookingRepository.java`, shared `J/support/idempotency/IdempotencyExecutor.java` và `JdbcIdempotencyStore.java` thuộc SC-03; `T/repair/application/GuestBookingServiceTest.java` và `T/repair/PublicBookingIT.java`.
- **Steps:** dùng shared executor, scope `public.booking.create` không có user ID, payload canonical sau chuẩn hóa, replay trước kiểm tra slot; gọi chung customer onboarding; tạo booking ONLINE/CONFIRMED và service snapshot; lưu mã booking và response an toàn cùng transaction. SC-03 sở hữu key locking, hash, expiry/cleanup, timeout và retry deadlock. Guest Booking không thêm `JdbcBookingIdempotencyRepository` hoặc cleanup job riêng.
- **Tests:** cùng key commit/replay, khác hash, lần đầu rollback rồi request thứ hai, expiry, duplicate plate trước full, capacity, rollback toàn bộ, khóa timeout và deadlock retry tối đa hai lần.
- **Acceptance:** chỉ một booking cho một ý định trong 24h; mọi response replay bằng response gốc; dữ liệu nghiệp vụ và idempotency không thể lệch transaction.

### GB-05 — Tra cứu public an toàn

- **Files:** `J/repair/application/PublicBookingLookup.java`, `J/repair/domain/PublicBookingLookupRepository.java`, `J/repair/infrastructure/JdbcPublicBookingLookupRepository.java`; `T/repair/application/PublicBookingLookupTest.java`.
- **Steps:** xác thực XOR phone/code; phone chuẩn hóa rồi map qua customer; code exact normalized; phân trang ổn định; batch đọc snapshots; che plate server-side; projection allowlist.
- **Tests:** hai cách tra cứu, XOR invalid, no results, pagination, mã không tồn tại, snapshot, masked plate và assertion không có trường nhạy cảm.
- **Acceptance:** public response chỉ chứa dữ liệu đã cho phép và API là read-only.

### GB-06 — Public API, lỗi và rate limit

- **Files:** `J/repair/api/PublicBookingController.java`, `PublicBookingErrors.java`, `J/identity/infrastructure/PublicBookingRateLimitFilter.java`, cập nhật `SecurityConfiguration.java`; API/filter tests.
- **Steps:** expose hai POST; validation/error envelope; no-store cho lookup; exact security matcher; limiter bounded 4096 entry, expiry, proxy allowlist; không log body/PII.
- **Tests:** qua SecurityFilterChain thật: Guest được gọi public, session không bắt buộc, staff routes vẫn chặn; status/body/headers; limiter và spoof forwarding.
- **Acceptance:** API contract và security thực tế khớp tài liệu; không có public endpoint ghi ngoài booking.

### GB-07 — UI đặt lịch

- **Files:** `F/app/App.tsx`, `F/api/publicClient.ts`, `F/features/booking/BookingPage.tsx`, `bookingApi.ts` và CSS.
- **Steps:** public routes chạy trước kiểm tra `/auth/me`; chọn loại xe, dịch vụ, ngày/slot; nhập liên hệ/xe/ghi chú; giữ form khi slot đầy; tạo UUID và giữ cùng key/payload khi retry; không double submit; trang xác nhận cho lưu mã.
- **Tests:** `npm run build`; browser flow validate, refresh slot, submit/retry, full, stale slot và success.
- **Acceptance:** khách đặt được không đăng nhập; không gửi cookie session/CSRF; lỗi không làm mất thông tin đã nhập.

### GB-08 — UI tra cứu

- **Files:** `F/features/lookup/LookupPage.tsx`, `lookupApi.ts`, CSS và router `App.tsx`.
- **Steps:** chọn phone hoặc booking code; nhập tiêu chí, phân trang, loading/empty/error/retry; hiển thị đúng projection và trạng thái lịch.
- **Tests:** build và browser lookup theo phone/code, XOR UI, empty/error/pagination, xác nhận URL/storage không chứa PII.
- **Acceptance:** khách tra cứu không đăng nhập; không lộ ghi chú/điện thoại đầy đủ/internal ID.

### GB-09 — Nghiệm thu và đồng bộ tài liệu

- **Files:** `T/repair/PublicBookingIT.java`, `T/migration/FoundationMigrationIT.java` nếu có thay schema, `frontend/e2e/public-booking.spec.ts` nếu browser harness khả dụng; cập nhật `docs/requirements.md`, `docs/architecture.md`, `docs/erd.md`, backend/frontend README và handoff.
- **Steps:** chạy unit/API tests, build, SQL Server migration/integration test DB riêng, browser acceptance; xác nhận không thêm migration nếu schema nền đủ.
- **Tests:** cạnh tranh capacity/idempotency/customer, no PII, SecurityFilterChain, route public, browser guest flow, builds.
- **Acceptance:** tài liệu khớp code; nêu chính xác bằng chứng SQL Server/browser; không còn lỗi blocker về lưu trữ, bảo mật, race hoặc guest flow.

## Trình tự

`GB-01 → GB-02/GB-03 → GB-04/GB-05 → GB-06 → GB-07/GB-08 → GB-09`.

## Định nghĩa hoàn tất

Guest đặt lịch và tra cứu không cần tài khoản; customer/vehicle được giữ và tái sử dụng an toàn; slot và idempotency chống race trên SQL Server; response public chỉ lộ allowlist; UI, API, test/build và tài liệu đều đạt. Feature không bao gồm deploy production.
