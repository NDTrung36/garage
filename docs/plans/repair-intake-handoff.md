# Bàn giao Tiếp nhận xe và sửa chữa

Ngày 19/11/2026 · **SC-14 đạt nghiệm thu tích hợp local trên SQL Server và Chromium**. Backup/restore thuộc nghiệm thu vận hành AC-16 và còn chờ bằng chứng. Xem [kết quả SC-14](#kết-quả-sc-14) và [kế hoạch](repair-intake-plan.md#4-subtask-triển-khai).

## Mốc khởi đầu

- Repo `D:\Project\University\garage-sua-xe`, branch công việc `codex/repair-intake-sc01` tại `b61dd5d`. Branch được tạo từ working tree `codex/guest-booking-public-lookup`; thay đổi local được giữ nguyên.
- Lúc bắt đầu SC-02, branch là `codex/repair-intake-sc01` tại `b61dd5d`; lúc cập nhật handoff, worktree đang ở `codex/guest-booking-public-lookup`, cùng HEAD. Giữ nguyên working tree. V005 `parts`, V007 `stock_movements` thuộc kho; SC-02 thêm V006, V008, V009. Chưa đọc được `flyway_schema_history` do database test không mở được cho login. Owner đã thống nhất với [bàn giao Phụ tùng và tồn kho](phu-tung-ton-kho-handoff.md).
- Có session Staff/Admin, schema booking và API dịch vụ/slot. Cần đầu vào Guest Booking thật để nghiệm thu chuỗi online. Fixture `ONLINE/CONFIRMED` đủ để kiểm chứng riêng check-in khi tích hợp còn chờ.
- SC-01 đã đồng bộ hợp đồng và quyền sở hữu giữa requirements, architecture, ERD và kế hoạch Guest Booking, Phụ tùng–Tồn kho, Tiếp nhận–Sửa chữa. Đây là hoàn tất tài liệu, chưa có build, test nghiệp vụ, test SQL Server hay browser acceptance.

## Mục đầu tiên

**SC-01 — Hoàn tất.** Đã rà branch/commit, migration V001–V005, Guest Booking, slot/catalog và owner chung với INV-01. **SC-02–10 — Đã triển khai source/test; unit/API tests gần nhất đạt, SQL Server IT còn chờ database test được cấp quyền.**

| Điểm bàn giao | Kết quả SC-01 |
|---|---|
| Branch tích hợp và migrations lúc đối chiếu | `codex/repair-intake-sc01`; V001–V005 và V007 có mặt. V005 `parts` và V007 `stock_movements` thuộc kho. V006 repair, V008 audit, V009 invoice thuộc SC. Kiểm tra Flyway history trước khi triển khai. |
| Phạm vi hóa đơn và kho | Kế hoạch gồm invoice `UNPAID` và xuất/hoàn/đổi giá trên phiếu. Dùng danh mục và nhập kho từ INV-03/05. Thu tiền và quản trị kho đầy đủ bàn giao riêng. |
| File và DTO dùng chung với feature kho | INV-02 sở hữu V005/V007; SC-02 sở hữu V006/V008/V009. SC-03 sở hữu adapter idempotency/audit dùng chung. INV-06 và SC-09/10 dùng một bộ API xuất/hoàn/giá; INV-09 và SC-13 dùng một panel. Hợp đồng ghi ở hai kế hoạch. |
| Hành vi phiếu và schema bổ sung | Phiếu bắt đầu trống, LABOR có `voided_at/by`, check-in lịch còn `CONFIRMED`; ledger có `reason` bắt buộc và `note` nullable chỉ cho RECEIPT. Số lượng JSON là chuỗi DECIMAL(18,3); part/phiếu có version. Đã ghi trong plan, requirements và ERD. |
| Nhánh/ticket Guest Booking | `codex/guest-booking-public-lookup`, mốc `b61dd5d`. E2E online cần public POST thật → Staff list → check-in → repair order. Chưa phân công cá nhân. |
| SQL Server và tài khoản nghiệm thu | Chưa kiểm khả dụng. Chuẩn bị DB test riêng theo [backend README](../../backend/README.md) và tài khoản tổng hợp Staff/Admin. |

## Theo dõi thực hiện

| ID | Đầu ra chính | Trạng thái | Bằng chứng |
|---|---|---|---|
| SC-01 | Hợp đồng thống nhất, nhánh nền và dependency Guest Booking | Hoàn tất hợp đồng | Đối chiếu branch `codex/repair-intake-sc01`, owner migrations, adapter dùng chung và quyết định ở hai plan cùng requirements/architecture/ERD; E2E Guest Booking còn chờ |
| SC-02 | Schema chung INV-02 và migration invoice | Đã viết V006/V008/V009 và harness; còn chờ nghiệm thu SQL Server | `mvn -q -DskipTests test-compile` đạt; SQL Server IT chưa chạy xong do chưa có database test |
| SC-03 | Idempotency, audit và kết nối SQL ghi | Đã viết; unit suite đạt, SQL Server IT chưa chạy do thiếu URL DB test | `mvn -q test` đạt; chưa có `MIGRATION_TEST_URL` |
| SC-04 | Chuẩn hóa và dùng lại khách/xe, dịch vụ | Đã triển khai; unit suite đạt, SQL Server IT chờ DB test | `mvn -q test` đạt 76 test; chưa có `MIGRATION_TEST_URL` |
| SC-05 | Danh sách lịch và check-in online | Đã thêm implementation và test; kiểm chứng đang chờ môi trường | Controller, query, stores và intake service; API/unit source; `OnlineCheckInIT`; `mvn -q -DskipTests test-compile` bị chặn khi resolve Spring Boot parent từ Maven Central; chưa có `MIGRATION_TEST_URL` |
| SC-06 | Walk-in nguyên tử | Đã thêm implementation/API/unit/SQL Server IT; kiểm chứng chờ môi trường | `VehicleIntakeService`, `JdbcBookingStore`, `/api/staff/walk-ins`, `WalkInIntakeIT`; Maven parent không tải được, DB test URL chưa có |
| SC-07 | Phiếu, LABOR và tính tiền | Đã thêm implementation và test source; compile/test bị chặn bởi Maven Central | Repair order query/API, LABOR service/repository, shared totals; `RepairOrdersApiTest`, `RepairLaborIT`; chưa chạy test |
| SC-08 | Tích hợp danh mục và nhập tồn của INV-03/05 | Đã đối chiếu API dùng chung; MockMvc/API-security tests đạt, smoke SQL Server/backend thật còn chờ đăng nhập DB | `InventoryApiTest`, `InventorySecurityTest`, `PartTest`, `StockQuantityTest`: 22 test đạt; port API 8080 không chạy; SQL Server 51433 mở nhưng Windows Integrated Authentication lỗi, chưa đặt `MIGRATION_TEST_URL` |
| SC-09 | Xuất kho vào phiếu | Implementation INV-06 đã tích hợp; API/security/totals tests đạt, SQL Server IT chặn do DB test không mở được | `RepairPartsApiTest`, `RepairPartsSecurityTest`, `RepairTotalsTest`: 11 test đạt; `RepairStockSqlServerIT` dừng ở setup DB `garage_migration_test` trước migration |
| SC-10 | Hoàn kho và đổi giá PART | RETURN/price implementation đã điều chỉnh audit và test source; 11 API/security/totals tests đạt; SQL IT chặn do DB test không mở được | `REPAIR_ITEM` audit lưu quantity/giá/tổng trước-sau; IT source hoàn partial/full/replay/inactive/concurrent và kiểm giá; SQL test dừng ở setup `garage_migration_test` |
| SC-11 | Hoàn tất và sinh hóa đơn | Đã triển khai và kiểm chứng | 22 unit/API test và 14 SQL Server IT đạt trên schema V009 |
| SC-12 | Giao diện tiếp nhận | Đã triển khai, build và smoke với API giả lập đạt | E2E với backend và SQL Server thật thuộc SC-14 |
| SC-13 | Giao diện phiếu sửa và hóa đơn | Đã triển khai, build và smoke với API giả lập đạt | E2E với backend thật, SQL Server và stale hai tab thuộc SC-14 |
| SC-14 | Nghiệm thu SQL Server, quyền và browser | Đạt tích hợp local, gồm online qua API Guest Booking thật | 156 unit/API, 70 SQL Server IT, build và 1 kịch bản Chromium đi qua cả hai luồng |

## Kết quả SC-14

Chạy ngày 19/11/2026 trên SQL Server Express 2022. Dùng hai DB riêng cho profile SQL Server (migrate sạch và nâng V004), cùng một DB khác cho lượt Chromium nghiệm thu. Vite 5184 chuyển tiếp sang Spring Boot 18086. Dữ liệu tổng hợp được tạo qua Admin API, Staff UI và Guest Booking API. Booking online được tạo bằng `POST /api/public/bookings` trên backend thật.

| Lớp kiểm thử | Lệnh và kết quả |
|---|---|
| Unit/API và bảo mật | `mvn test` đạt **156/156**. `RepairSecurityTest` chạy qua SecurityFilterChain thật và kiểm Guest, Staff, Admin, CSRF và phiên lỗi `authVersion`. Browser xác nhận thêm thay đổi tài khoản qua Admin API làm phiên Staff thật nhận `401 SESSION_REVOKED`. |
| Migration | `mvn -Psqlserver-it verify` đạt **70/70 SQL IT**, gồm 20 ca `FoundationMigrationIT`. Áp V001–V009, rerun 0 migration, nâng V004 → V009 giữ booking `CONFIRMED` và snapshot. DB browser cũng chạy ca hợp đồng migration trước bootstrap. Kiểm SQL xác nhận đúng tên DB, 17 bảng cộng Flyway history, 9 migration thành công và các bảng nghiệp vụ rỗng trước fixture. |
| Transaction và cạnh tranh | Online 2, walk-in 1, LABOR 1, sửa kho 6, hoàn tất 6, cạnh tranh 9, idempotency 8 đều đạt trong profile. Worker chạy trên transaction độc lập, có barrier và giới hạn chờ. Kiểm trạng thái và hóa đơn bằng SQL sau commit. |
| Build và browser | `npm run build` đạt. `npm run test:e2e` đạt **1/1** kịch bản Chromium bao gồm cả hai đường chạy với Vite proxy, backend và DB thật. Hai lần Playwright chủ động ngắt response sau commit để kiểm gửi lại walk-in và ISSUE. |

Maven dùng thêm `-o '-Dmaven.repo.local=../.cache/maven'` trên máy kiểm thử với `MIGRATION_TEST_URL` và `MIGRATION_TEST_V004_URL` dành riêng. Lượt profile đầu tái hiện 9 lỗi. Đã sửa cách khởi tạo fixture và ObjectMapper, chia sẻ DataSource trong test rollback, khai báo servlet path cho MockMvc, tránh job sinh lịch làm nhiễu kiểm seed, dùng lệnh SQL phù hợp để đọc SHOWPLAN và sửa assertion. Ca tranh tồn hiện dùng hai phiếu khác nhau. Chạy lại toàn profile trên hai DB mới đạt. Đã dừng backend test và dọn các DB tạm do SC-14 tạo sau khi đối soát.

| Tiêu chí §5 | Bằng chứng |
|---|---|
| AC-08, AC-19 | Guest gọi check-in nhận 401. Staff tiếp nhận lịch online đã đóng và walk-in thành `IN_PROGRESS`. Check-in lặp trả lại ID phiếu. Hai lần bấm walk-in chỉ phát một request ban đầu, retry dùng đúng key/payload. Sau chốt, nút ghi công biến mất và gọi API ghi trực tiếp trả 409. |
| AC-09 | Browser thêm, sửa và bỏ LABOR; stale hai tab trả lỗi và giữ bản nhập. Đổi giá catalog từ 101 lên 151, snapshot online vẫn hiển thị 101. |
| AC-10, AC-11, AC-18 | Browser kiểm thiếu tồn, retry ISSUE, hoàn theo lần xuất gốc và đổi giá có lý do. SQL xác nhận một ISSUE −1.500, một RETURN +0.500 tham chiếu ISSUE, tồn 1.000, net 1.000 ở giá 202 và một audit đổi giá. IT kiểm hai phiếu tranh tồn, tranh hạn mức hoàn, giữ giá snapshot và hoàn hết rồi xuất lại. |
| AC-12, AC-23 | Browser chốt online và walk-in. SQL xác nhận đúng hai hóa đơn `UNPAID` lần lượt 101 ₫ và 253 ₫ cho hai phiếu. IT kiểm tổng 0, làm tròn nửa đồng theo dòng, tổng cố định, rollback khi ghi invoice lỗi và một invoice khi hai lệnh chốt đua nhau. |
| AC-15, AC-21 | Admin tạo phụ tùng và nhập tồn. Staff mở trực tiếp trang Admin bị từ chối và POST trực tiếp bị 403. Thiếu CSRF trả 403. Khóa Staff làm API phiếu trả 401; sau khi mở khóa, đăng nhập lại trên URL phiếu giữ được màn hình hóa đơn. |
| AC-16 | Đạt phần Flyway và seed. **Backup/restore môi trường đích chưa chạy**, thuộc bằng chứng vận hành riêng. |
| AC-17 | Slot online có một lượt trước và sau check-in/chốt. SQL xác nhận walk-in không gắn slot. |
| AC-20 | `IdempotencySqlServerIT` đạt các ca chờ commit/rollback, conflict và timeout. Chromium xác nhận gửi lại cùng key và payload sau mất phản hồi; SQL xác nhận một kết quả ISSUE đã commit. |
| AC-24 | `RepairConcurrencyIT` đạt cả hai thứ tự check-in lặp/chốt và sửa LABOR/xuất/hoàn so với chốt. Trạng thái không lùi, hóa đơn duy nhất và tổng phản ánh dữ liệu được chốt. |

## Cập nhật khi kết thúc mỗi subtask

Thêm ngày, người thực hiện, nhánh/mốc code, file đã đổi, quyết định khác kế hoạch, lệnh và kết quả, mã AC được chứng minh, cùng bước tiếp theo. Đánh dấu đạt khi có bằng chứng tương ứng trong [ma trận nghiệm thu](repair-intake-plan.md#5-ma-trận-nghiệm-thu). Ghi riêng kết quả E2E với booking fixture và E2E có booking tạo qua API thật.

| Loại bằng chứng | Lệnh hoặc kịch bản | Kết quả |
|---|---|---|
| Unit/API và quyền | `mvn test`, gồm SecurityFilterChain và CSRF | Chưa chạy |
| Schema SQL Server | `mvn -Psqlserver-it verify`, DB sạch, chạy lại và nâng từ V004 | Chưa chạy |
| Transaction SQL Server | Commit/rollback, replay, đua ISSUE/RETURN và check-in/hoàn tất | Chưa chạy |
| Frontend typecheck/build | `npm run build` | Chưa chạy |
| Browser | Walk-in và online từ API Guest Booking, DB test riêng | Chưa chạy |

### SC-02 · 18/11/2026

- Đã thêm V006 `repair_orders`/`repair_items`, V008 `audit_logs`, V009 `invoices`. Giữ V005/V007 hiện hữu. V006 thêm cặp void đồng thời và lọc LABOR; V009 kiểm tra rõ các trường PAID không null. Không backfill booking `CONFIRMED`.
- Đã cập nhật migration README, backend README và `FoundationMigrationIT` cho schema V009, DB name/table set/Flyway history guard, defaults, FK/unique/filter/CHECK, ràng buộc ledger, audit JSON, thanh toán và nâng V004 có booking fixture.
- Kiểm tra version trên branch: scripts V001–V007 có mặt; chưa đọc được `flyway_schema_history` do database test `garage_migration_test` chưa mở được cho login. Không áp migration lên database nào.
- Kiểm tra đã chạy: `mvn -q -DskipTests test-compile` đạt. `mvn -Psqlserver-it verify` dừng tại unit test có sẵn `InventoryApiTest.staffCanReadButCannotWriteCatalog` (mock `partRepository` bị gọi khi GET list). Chạy riêng Failsafe tới `FoundationMigrationIT` nhưng kết nối báo không mở được database `garage_migration_test`; hai migration database chưa được nghiệm thu.
- Còn thiếu bằng chứng: migrate sạch + rerun 0 migration; nâng riêng V004 → V009 và giữ booking/snapshot/customer/slot; toàn bộ ràng buộc trên SQL Server. Cần tạo/cấp quyền cho `garage_migration_test` và `garage_migration_upgrade_test`, sau đó chạy lệnh trong [hướng dẫn migration](../../backend/src/main/resources/db/migration/README.md).

### SC-03 · 18/11/2026

- Đã thêm executor/store idempotency dùng chung, khóa PK composite với VARCHAR, fingerprint SHA-256 canonical, scope actor/use case, response thành công 24 giờ, thay key hết hạn và cleanup theo batch. Thêm transaction wrapper với session SET cho Hikari, `LOCK_TIMEOUT` 5 giây được khôi phục trước khi trả connection; SQL Server 1205 retry ngoài transaction tối đa hai lần; 1222 chuyển thành lỗi retryable `503` sau rollback.
- Đã thêm `AuditWriter`/JDBC adapter yêu cầu transaction đang mở, serialize snapshot JSON, API kho truyền actor từ principal và ghi audit trong transaction tạo/sửa part. Thêm advice repair riêng và gỡ mapping toàn cục `DataIntegrityViolationException` thành `USERNAME_EXISTS`; identity chỉ map lỗi unique username.
- Test đã thêm/đổi: replay và canonical hash; key/actor phân vùng; timeout mapping và `Retry-After`; deadlock/timeout restore unit test; SQL Server IT cho hai connection commit/rollback/timeout, cleanup/expiry, lock seek, audit+version rollback, session SET và filtered PART unique index qua Hikari write pool.
- Kiểm tra: `mvn -q "-Dtest=IdempotencyExecutorTest,SqlServerWriteTransactionsTest,RepairErrorsTest,InventoryApiTest,GuestBookingServiceTest" test` đạt; `mvn -q test` đạt toàn bộ unit suite (**58 test**, 0 failure/error); `mvn -q -DskipTests test-compile` đạt, gồm compile SQL Server IT; `git diff --check` không có lỗi whitespace. SQL Server IT chưa chạy vì môi trường không có `MIGRATION_TEST_URL`; không áp migration hay ghi DB. Một lần chạy unit đầu tiên phát hiện assertion sai trong test kho (GET list vốn gọi repository) và test timeout dùng Mockito stub cũ; đã sửa rồi chạy lại đạt.
- Còn thiếu bằng chứng SC-03: SQL Server IT thực tế gồm khôi phục timeout trên connection pool, index filtered qua connection ghi, rollback audit/version, chờ commit/rollback, 1222/Retry-After và cleanup. Cần có DB chuyên dụng theo [hướng dẫn backend](../../backend/README.md#integration-test-migration), rồi chạy `mvn -Psqlserver-it -Dit.test=IdempotencySqlServerIT verify`; SC-02 vẫn cần chạy migration IT sạch/rerun và nâng V004.

### SC-04 · 18/11/2026

- Đã thêm `customer.domain.ContactAndVehicle` để chuẩn hóa tên, số di động VN, biển số hiển thị/chuẩn hóa, hãng và model. Tên giữ 2–100 ký tự theo giới hạn NVARCHAR; phone về E.164; biển số bỏ whitespace/dấu chấm/gạch nối để so khớp, chỉ còn A–Z/0–9. Guest Booking dùng lại value object này.
- Đã thêm `CustomerVehicleStore`, `JdbcCustomerVehicleStore` và `CustomerVehicleOnboarding`. Adapter khóa dải unique phone rồi unique `(customer_id, plate_normalized)`, dùng VARCHAR cho phone và NVARCHAR cho biển số; hồ sơ hiện có không bị ghi đè, xe cùng biển của khách khác được phép, cùng khách nhưng đổi loại xe bị từ chối. Kết quả trả ID và snapshot request để caller ghi booking/phiếu cùng transaction.
- Catalog giờ có `BookingServiceLookup` domain port và `JdbcBookingServiceLookup`; `ServiceCatalog` cung cấp application contract cho Guest Booking/repair, bắt buộc 1–50 ID hợp lệ, không trùng, đang active và hợp loại xe hoặc `BOTH`, trả tên/giá snapshot hiện tại. Guest Booking đã đổi sang hai hợp đồng dùng chung này.
- Đã thêm unit test ContactAndVehicle/ServiceCatalog và SQL Server IT cho reuse không ghi đè, biển trùng khác customer, mismatch loại xe, race cùng phone/plate, rollback caller, lọc dịch vụ và snapshot giá. Đã thay adapter GuestCustomer cũ bằng hợp đồng chung; cập nhật `FoundationMigrationIT` sang wiring mới.
- Kiểm tra: `mvn -q test` đạt 76 test, 0 failure/error; `git diff --check` sạch. Test SQL Server `CustomerVehicleOnboardingIT`, `BookingServiceLookupIT` chưa chạy vì không có `MIGRATION_TEST_URL`; chỉ biên dịch ở pha `mvn test`, không kết nối hay sửa database. Có sửa nhỏ compile/test harness kho đang hiện trên worktree (import parse ID đúng trong repair controller, helper `key`/`await`, JUnit assertions và đăng ký JavaTime module cho ObjectMapper test).
- Còn thiếu bằng chứng: chạy hai SC-04 integration test trên `garage_migration_test` và chạy FTI booking guest qua cùng `CustomerVehicleOnboarding`/`ServiceCatalog`. Cần cấu hình URL DB riêng theo [hướng dẫn backend](../../backend/README.md#integration-test-migration); không dùng DB ứng dụng.

### SC-05 · 18/11/2026

- Đã thêm `StaffBookingsController`/`StaffBookingQuery` cho danh sách nội bộ theo ngày địa phương `Asia/Ho_Chi_Minh`, lọc trạng thái, phân trang ổn định theo thời điểm sự kiện rồi ID, và projection snapshot dịch vụ/phiếu. Phản hồi không cache.
- Đã thêm JDBC stores cho booking/phiếu và `VehicleIntakeService`: đọc slot hint → khóa slot → khóa booking; bỏ qua lead time/capacity/đóng lịch với booking đã giữ chỗ; chỉ nhận `ONLINE/CONFIRMED`; ghi nhận actor, Clock, km và ghi chú; tạo phiếu `IN_PROGRESS`, chuyển booking sang `CHECKED_IN` trong cùng transaction. Replay `CHECKED_IN`/`COMPLETED` trả phiếu hiện tại trước xét version, thiếu phiếu thành lỗi invariant. Lỗi lock/deadlock trả 503 và `Retry-After`.
- Test source: `StaffBookingsApiTest` cho xác thực/CSRF, actor, payload và no-store; `VehicleIntakeServiceTest` cho thứ tự khóa, snapshot, replay, stale và trạng thái; `OnlineCheckInIT` cho SQL Server, lịch đóng/quá giờ, một phiếu, replay, capacity, danh sách/snapshot và rollback/stale slot.
- Kiểm tra: `mvn -q -DskipTests test-compile` đã thử nhưng Maven không resolve được `spring-boot-starter-parent:3.5.16` vì kết nối Maven Central bị từ chối (`Permission denied: getsockopt`). Do đó chưa có kết quả compile/test cho SC-05. Môi trường cũng chưa có `MIGRATION_TEST_URL`; SQL Server IT chưa chạy. Không có ghi DB.
- Còn thiếu bằng chứng: mở được Maven dependency source/cache, chạy API/unit test; cấp SQL Server test DB, chạy `OnlineCheckInIT` và kiểm thử đầy đủ hai phiên, trạng thái `CANCELLED`/`NO_SHOW`, đổi slot cạnh tranh, ranh giới ngày và các giá trị biên trên DB. Chưa xác nhận browser flow từ Guest Booking API thật.

### SC-06 · 18/11/2026

- Đã thêm `POST /api/staff/walk-ins`, dùng principal Staff/Admin lấy actor và bắt buộc `Idempotency-Key` + CSRF theo security chain hiện có. Payload chuẩn hóa liên hệ/xe qua `ContactAndVehicle`, sắp service IDs để fingerprint ổn định, kiểm tra catalog active/tương thích, km/ghi chú và snapshot giá/dịch vụ.
- Walk-in dùng `IdempotencyExecutor` scope theo use case + actor; cùng transaction tạo/tái dùng customer/vehicle, booking `WALK_IN/CHECKED_IN` không slot, service snapshots và phiếu `IN_PROGRESS`. Response HTTP 201 được lưu; retry trả nguyên record đã lưu. Mã `BK-` có 12 ký tự uppercase/số. Adapter chỉ phân loại collision có tên `UX_bookings_code`; service rollback lần đó rồi gọi lại executor để retry trong transaction mới, tối đa 3 lần. Constraint khác và lỗi khóa không đi qua nhánh retry mã.
- Đã thêm kiểm thử API cho key/CSRF, actor, chuẩn hóa phone, service ID và validation; unit test cho collision retry với code mới; `WalkInIntakeIT` kiểm tra replay sau mất response, tái dùng phone `0…`/`+84…`, nhiều xe cùng customer, lỗi service và rollback customer/booking/phiếu/idempotency key. Integration test dùng DB-name guard, Flyway validate/migrate và dữ liệu test riêng; kiểm tra số booking có slot trước/sau.
- Kiểm tra: `git diff --check` đạt. `mvn -q -DskipTests test-compile` đã thử lại nhưng bị chặn trước khi đọc project bởi Maven Central từ chối tải `spring-boot-starter-parent:3.5.16` (`Permission denied: getsockopt`). Chưa có `MIGRATION_TEST_URL`; không chạy SQL Server IT hay ghi DB.
- Còn thiếu bằng chứng: compile/unit/API tests và `WalkInIntakeIT` trên SQL Server test; chưa kiểm chứng thực tế transaction mới khi code trùng, hai session concurrent, browser giữ nguyên key sau mất response và mở phiếu từ Staff UI.

### SC-07 · 18/11/2026

- Đã thêm GET danh sách/chi tiết phiếu theo trang ổn định, tách snapshot dịch vụ đặt ban đầu, LABOR, PART, thông tin tiếp nhận và ghi chú. PATCH header giữ phân biệt field vắng/null; ghi chú rỗng chuẩn hóa về null. API ghi yêu cầu Staff/Admin và CSRF theo security chain.
- Đã thêm LABOR add/update/soft-delete dưới khóa phiếu và kiểm version; DELETE đặt `voided_at/by`, chỉ cho phiếu `IN_PROGRESS`. Kiểm tra loại dòng và repair order ngăn endpoint LABOR tác động PART hoặc item của phiếu khác. ServiceId tùy chọn được kiểm qua catalog hợp đồng dùng chung.
- `RepairTotals` làm tròn từng dòng `HALF_UP`, bỏ LABOR void và PART số lượng 0; kiểm giới hạn SQL DECIMAL và safe integer ở giá, dòng và tổng. Tổng được tính trước khi bump version/commit nên vượt giới hạn sẽ rollback thao tác.
- Đã thêm `RepairTotalsTest`, `RepairOrdersApiTest`, `RepairLaborIT` cho rounding/biên, API-auth/CSRF/validation, stale version, sai item, soft-delete, khóa trạng thái hoàn tất, snapshot booking services/catalog và rollback khi vượt ngưỡng.
- Kiểm tra: `git diff --check` đạt. `mvn -q -DskipTests test-compile` chạy tại `backend/` nhưng bị chặn trước compile do Maven Central từ chối tải parent `spring-boot-starter-parent:3.5.16` (`Permission denied: getsockopt`). Không có `MIGRATION_TEST_URL`; chưa chạy unit/API hay SQL Server IT, không ghi DB.
- Còn thiếu bằng chứng: compile và chạy `RepairTotalsTest`, `RepairOrdersApiTest`, `RepairLaborIT` sau khi dependency và DB test khả dụng. Chưa đạt nghiệm thu runtime. Tiếp theo là SC-08 ghép kho/danh mục vào luồng chi tiết rồi SC-09/10 tích hợp dòng PART với cùng version/tổng.

### SC-08 · 18/11/2026

- Đối chiếu code INV-03/05: Staff/Admin đọc danh mục theo trạng thái/loại xe; Admin tạo part tồn `0` và nhập tồn qua `/api/admin/stock-receipts`. Lệnh receipt dùng key theo actor/use case, khóa part, cập nhật số dư/version và ghi ledger trong cùng transaction; actor lấy từ `GaragePrincipal`. Ghi chú và lý do được trim/kiểm độ dài; số lượng dùng `DECIMAL(18,3)`.
- Chạy `mvn -q "-Dtest=InventoryApiTest,InventorySecurityTest,StockQuantityTest,PartTest" test`: **22 test đạt, 0 lỗi** (13 API, 4 SecurityFilterChain/CSRF, 2 Part, 3 StockQuantity). Build cũng compile toàn bộ main/test source. Trong quá trình compile đã bổ sung import `java.util.List` còn thiếu ở `JdbcRepairPartRepository` và tham chiếu đúng nested `ClockConfig` trong hai security test.
- Smoke với backend/SQL Server thật chưa chạy: không có process lắng nghe port 8080; SQL Server đang lắng nghe `localhost:51433`, nhưng JDBC Windows Integrated Authentication trả lỗi `-2146893042`; biến `MIGRATION_TEST_URL` chưa đặt. Đã thử truy vấn chỉ đọc trên `master`, không kết nối được; không áp migration hoặc ghi dữ liệu.
- Bằng chứng source có sẵn ở `InventorySqlServerIT`: part mặc định tồn 0/trùng mã/version; nhập `2.500`, điều chỉnh chính xác và replay; rollback khi ledger lỗi; hai lệnh điều chỉnh cạnh tranh; khóa đổi đơn vị khi receipt đầu tiên. Chưa có kết quả chạy các ca này trên SQL Server.
- Còn thiếu để đóng nghiệm thu SC-08: cấu hình quyền Windows/JDBC hoặc thông tin DB test dành riêng; tạo/cấp quyền database `garage_migration_test`, đặt `MIGRATION_TEST_URL`; chạy `InventorySqlServerIT` sau migration rồi smoke Admin tạo part và receipt qua API thật, xác nhận balance, version, ledger và retry cùng key.

### SC-09 · 18/11/2026

- Đối chiếu INV-06: ISSUE khóa phiếu `IN_PROGRESS`, khóa part trước tạo dòng PART/FK, lấy snapshot mô tả/đơn vị/giá lần đầu; lần xuất sau cộng vào cùng dòng và giữ giá gốc. Inventory ghi ISSUE âm, giảm tồn và tăng version part; repair tăng quantity/version phiếu và trả ledger, tồn, dòng PART, tổng. Thao tác cùng response được xử lý trong giao dịch idempotency.
- SQL IT source bao phủ replay cùng key; nhiều lần xuất cùng phụ tùng trên phiếu và giữ giá khi catalog đổi; part inactive/sai loại xe/thiếu tồn; hai phiếu cạnh tranh lượng cuối; tổng vượt ngưỡng rollback tồn/ledger/item/version/key. API tests kiểm controller, validation/conflict; security test dùng filter thật; totals test kiểm rounding/giới hạn.
- Chạy `mvn -q "-Dtest=RepairPartsApiTest,RepairPartsSecurityTest,RepairTotalsTest" test`: **11 test đạt, 0 lỗi** (4 API, 3 SecurityFilterChain, 4 totals). Build compile toàn bộ main/test source.
- Chạy `mvn -q -Psqlserver-it "-Dit.test=RepairStockSqlServerIT" verify` với URL trỏ `garage_migration_test`. Failsafe dừng tại `migrateDedicatedDatabase`: SQL Server không mở database này cho login. Lỗi trước DB guard/Flyway migrate; không ghi dữ liệu.
- Còn thiếu: tạo/cấp quyền database test hoặc credentials SQL, rồi chạy lại `RepairStockSqlServerIT` để chứng minh rollback, race hai phiếu và ledger thật. Smoke HTTP backend cũng chờ app và DB test. PATCH catalog có API test riêng; SQL IT hiện cập nhật giá catalog trực tiếp làm fixture để kiểm snapshot dòng PART.

### SC-10 · 18/11/2026

- Sửa audit đổi giá: `entity_type=REPAIR_ITEM`; before/after JSON gồm quantity scale 3, unit price, line total và tổng phiếu; `actor_user_id` và reason nằm trên audit row. Giá không đổi vẫn trả version hiện tại và không ghi audit/version.
- Mở rộng `RepairStockSqlServerIT`: hoàn partial rồi full theo đúng ISSUE, giữ movement gốc, cho hoàn part inactive, cùng key replay trả response cũ, từ chối vượt lượng còn lại, phiếu khác và movement RECEIPT, hoàn đồng thời không vượt ISSUE gốc. Giá PART cập nhật toàn dòng, giữ quantity/tồn/ledger, ghi một audit có đủ snapshot; cùng giá không tăng version/audit; thiếu reason/stale/sai loại và audit lỗi rollback được.
- Chạy `mvn -q "-Dtest=RepairPartsApiTest,RepairPartsSecurityTest,RepairTotalsTest" test`: **11 test đạt, 0 lỗi**. Test phase compile cả integration source. Chạy `mvn -q -Psqlserver-it "-Dit.test=RepairStockSqlServerIT" verify`: Failsafe dừng tại kết nối vì SQL Server không mở được `garage_migration_test` cho login; không tới Flyway/fixture, không ghi DB.
- Còn thiếu bằng chứng SQL thật cho ca hoàn/replay/concurrency/audit rollback. Cấp quyền DB test rồi chạy lại `RepairStockSqlServerIT`; chưa đánh dấu runtime acceptance hoàn tất trước khi test này đạt.

### SC-11 · 18/11/2026

Đối chiếu HD-06 ngày 19/11/2026. `RepairCompletionIT` (6) và `RepairConcurrencyIT` (9) đạt trên SQL Server V009 trong lượt chạy toàn profile. Hoàn tất giữ thứ tự khóa booking → phiếu → invoice. Hoàn tất lặp và chi tiết phiếu đã chốt trả summary của hóa đơn hiện tại. UI phiếu liên kết sang hóa đơn nội bộ. Xem bằng chứng AC-12/23 và giới hạn của bộ SQL đầy đủ tại [handoff billing](hoa-don-thanh-toan-handoff.md#kết-quả-hd-06--19112026).

- API `POST /api/staff/repair-orders/{id}/complete` khóa booking → phiếu, đối chiếu net từng PART với ledger và tính lại tiền theo từng dòng hiệu lực. Cùng transaction chuyển booking và phiếu sang `COMPLETED`, ghi actor/thời điểm và tạo hóa đơn `UNPAID`. GET chi tiết đọc hóa đơn qua billing.
- Khi mã hóa đơn trùng, thử tối đa ba giao dịch. Phiếu đã chốt trả hóa đơn hiện tại kể cả khi request mang version cũ. Nếu một chỉnh sửa commit trước khi chốt phiếu đang mở, API trả `409 STALE_VERSION`. Gửi lại version mới sẽ tính khoản vừa ghi vào hóa đơn.
- Chạy `mvn -q -Psqlserver-it "-Dtest=RepairOrdersApiTest,RepairPartsApiTest,RepairPartsSecurityTest,RepairTotalsTest,VehicleIntakeServiceTest" "-Dit.test=RepairCompletionIT,RepairConcurrencyIT" verify` trên branch `codex/guest-booking-public-lookup`. Đạt 22 unit/API test và 14 SQL Server IT. Đã áp V001–V009 trên database tạm riêng, lần migrate sau áp 0 migration và database tạm đã được dọn sau lượt test.
- IT xác nhận hóa đơn tổng 0 và làm tròn từ .5, giữ giá sau khi đổi catalog, rollback khi lỗi invoice insert, retry mã trùng, phát hiện ledger lệch và một hóa đơn cho hai lần chốt đồng thời. Các ca cạnh tranh kiểm cả hai thứ tự với check-in lặp, xuất, hoàn và LABOR, đồng thời xác nhận giữ lượt slot. Nghiệm thu trình duyệt thuộc SC-14.

### SC-12 · 19/11/2026

- Thêm `/staff/bookings` và `/staff/walk-ins` cho Staff/Admin. Lịch lọc ngày theo giờ gara và trạng thái, giữ bộ lọc khi dùng Back. Chỉ hiển thị tiếp nhận cho lịch `CONFIRMED`. Lịch đã tiếp nhận hoặc hoàn tất mở phiếu hiện có. Khi lịch đổi version, tải lại và giữ dữ liệu nhận xe để nhân viên đối chiếu trước khi xác nhận.
- Walk-in tải dịch vụ theo loại xe, bỏ lựa chọn không còn phù hợp và chỉ mở phiếu khi API trả ID hợp lệ. Yêu cầu chưa xác nhận được giữ theo tài khoản trong `sessionStorage` để thử lại cùng key và payload trong cùng tab, kể cả sau reload hoặc đăng nhập lại. Khi đã qua 24 giờ kể từ lần gửi đầu, yêu cầu đối soát phiếu trước khi bắt đầu lượt mới. API client đọc `fieldErrors` và dùng hỗ trợ `Retry-After`, HTTP 204 có sẵn.
- `npm run build` tại `frontend/` đạt. Smoke Chromium với Vite và API giả lập đạt các ca điều hướng trực tiếp, menu Staff/Admin, Back, loading/empty/error, bộ lọc ngày và trạng thái, tiếp nhận lịch đóng trong fixture, stale giữ dữ liệu, mở phiếu, lỗi theo trường, đổi loại xe, nhấp đúp, mất phản hồi, đăng nhập lại và logout 204. Ba lần gửi trong ca mất phản hồi/đăng nhập lại dùng đúng một key và payload.
- E2E SC-14 cần xác nhận tương tự với backend và SQL Server thật, gồm một phiếu được lưu và kiểm tra sức chứa slot sau tiếp nhận.

### SC-13 · 19/11/2026

- Tách danh sách có bộ lọc trạng thái và phân trang, chi tiết hồ sơ nhận xe, dịch vụ yêu cầu và công việc thực tế. UI hỗ trợ sửa hồ sơ nhận xe, thêm/sửa/bỏ LABOR với version, kiểm phạm vi và số lẻ trước khi gửi. Tổng và trạng thái hóa đơn lấy từ backend.
- Gắn panel INV-09 cho xác nhận xuất, hoàn theo ISSUE gốc và sửa giá có lý do. Lệnh kho lưu key và payload theo tài khoản và phiếu trước khi gửi. Retry giữ lệnh qua reload hoặc hết phiên. Sau khi ghi hoặc replay, UI đọc lại chi tiết và danh mục hiện tại. Các lần tải chi tiết mới hủy lần đọc trước.
- Chốt phiếu qua hộp xác nhận có version. Sau khi chốt, giao diện hiển thị thông tin ở chế độ chỉ đọc và mã, tổng tiền, trạng thái thanh toán của hóa đơn.
- `npm run build` đạt. Smoke Chromium với API giả lập kiểm mở phiếu bằng bàn phím, sửa hồ sơ, thêm/sửa/bỏ LABOR, stale giữ dữ liệu, tồn thiếu, mất phản hồi rồi reload và replay đúng key/payload, hoàn theo ISSUE, lỗi CSRF/403, sửa giá, chốt, refresh hóa đơn ở chế độ chỉ đọc và 401 về đăng nhập.
- E2E SC-14 còn cần xác minh các luồng bằng backend và SQL Server thật, gồm stale từ hai tab và đối soát vật tư/hóa đơn sau chốt.

### INV-06 integration · 18/11/2026

- Đợt kho đã thêm contract `RepairStockOperations`, cùng Repair dùng `RepairOrderStore.findByIdForUpdate` để khóa phiếu. Đọc snapshot `vehicle_type_snapshot` trước khi khóa phiếu để giữ thứ tự tránh chốt (booking → order) bị đảo; sau khóa phiếu kho khóa part rồi mới khóa ISSUE gốc khi hoàn.
- `JdbcRepairPartRepository` bổ sung dòng PART, tổng phiếu và version; `RepairPartCommands/Query` xử lý ISSUE, RETURN, giá, `returnableIssues`; `RepairTotals` làm tròn từng dòng. `RepairPartsController` cung cấp route `/api/staff/repair-orders/{id}/parts` và các lệnh ISSUE/RETURN/PATCH giá. Vì `RepairOrdersController` chưa có trên branch, route GET hiện là subresource để sau này gắn vào DTO GET chi tiết đầy đủ khi SC-07 tạo controller.
- Tái dùng `AuditWriter`/`JdbcAuditWriter` hiện có. Đã thêm `RepairTotalsTest`, `RepairPartsApiTest`, `RepairStockSqlServerIT` cho line rounding, replay, snapshot, hoàn hết/xuất lại, sai phiếu/trạng thái, audit rollback và race xuất.
- `git diff --check` đạt. `mvn -Psqlserver-it verify` dừng trước compile vì Maven Central từ chối tải Spring Boot parent 3.5.16 (`Permission denied: getsockopt`); `MIGRATION_TEST_URL` chưa được cấu hình nên SQL Server IT chưa chạy. Chưa có use case complete/invoice trên branch để xác nhận đua xuất/hoàn với chốt; SC-11 cần gọi `RepairTotals` chung dưới thứ tự khóa booking → phiếu.
