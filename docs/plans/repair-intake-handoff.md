# Bàn giao Tiếp nhận xe và sửa chữa

Ngày khởi tạo 18/11/2026 · **Kế hoạch đã lưu, triển khai chưa bắt đầu**. Nguồn công việc là [kế hoạch SC-01 đến SC-14](repair-intake-plan.md#4-subtask-triển-khai).

## Mốc khởi đầu

- Repo `D:\Project\University\garage-sua-xe`, nhánh `main`, HEAD `e815d85` tại lúc khảo sát. Worktree có thay đổi sẵn ở scheduler/slot, API dịch vụ, V004, test migration và tài liệu. Lấy lại `git status --short --branch` và xác định nhánh chứa phần lịch/catalog trước khi bắt đầu.
- `V004__add_schedule_overrides_and_service_version.sql` là migration cao nhất quan sát được trong worktree. Kiểm tra lại danh sách migration và điều phối với [bàn giao Phụ tùng và tồn kho](phu-tung-ton-kho-handoff.md) trước SC-02.
- Có session Staff/Admin, schema booking và API dịch vụ/slot. Cần đầu vào Guest Booking thật để nghiệm thu chuỗi online. Fixture `ONLINE/CONFIRMED` đủ để kiểm chứng riêng check-in khi tích hợp còn chờ.
- Kết quả của lượt lập kế hoạch là hai tài liệu trong `docs/plans/`. Chưa có kết quả build, test nghiệp vụ, test SQL Server hay nghiệm thu trình duyệt cho feature này.

## Mục đầu tiên

**SC-01 — Chốt hợp đồng và ghép nhánh lịch.** Đọc phần [quyết định đề xuất](repair-intake-plan.md#các-điểm-cần-xác-nhận-ở-sc-01), kiểm tra nhánh Guest Booking, phối hợp INV-01 và ghi lại lựa chọn ở bảng dưới. Khi mốc lịch/catalog đã xác định, thực hiện SC-02 và có thể bắt đầu SC-04. Người thực hiện và nhánh triển khai chưa được giao.

| Điểm bàn giao | Trạng thái lúc khởi tạo |
|---|---|
| Nhánh chứa lịch/catalog và version Flyway dành cho feature | Chưa xác nhận ngoài mốc worktree ở trên. Dự kiến dùng V005–V007 của INV-02, thêm V008 cho invoice. |
| Phạm vi hóa đơn và kho | Kế hoạch gồm invoice `UNPAID` và xuất/hoàn/đổi giá trên phiếu. Dùng danh mục và nhập kho từ INV-03/05. Thu tiền và quản trị kho đầy đủ bàn giao riêng. |
| File và DTO dùng chung với feature kho | INV-02 schema, INV-04 idempotency, INV-06 xuất/hoàn/giá và INV-09 panel. Chưa phân công người thực hiện. Mỗi hạng mục chung triển khai một lần, theo hợp đồng đã đối chiếu trong kế hoạch. |
| Hành vi phiếu và schema bổ sung | Đề xuất phiếu bắt đầu trống, sửa được số lượng LABOR ở bản nháp, bỏ LABOR có dấu vết `voided_*`, ledger có `note`, check-in mọi lịch còn `CONFIRMED`. Cần xác nhận tại SC-01. |
| Nhánh/ticket Guest Booking | Chưa có trong mốc khảo sát. Ghi liên kết và người phụ trách khi xác định để chạy E2E online. |
| SQL Server và tài khoản nghiệm thu | Chưa kiểm khả dụng. Chuẩn bị DB test riêng theo [backend README](../../backend/README.md) và tài khoản tổng hợp Staff/Admin. |

## Theo dõi thực hiện

| ID | Đầu ra chính | Trạng thái | Bằng chứng |
|---|---|---|---|
| SC-01 | Hợp đồng thống nhất, nhánh nền và dependency Guest Booking | Chờ bắt đầu | — |
| SC-02 | Schema chung INV-02 và migration invoice | Chờ SC-01, phối hợp INV-02 | — |
| SC-03 | Idempotency, audit và kết nối SQL ghi | Chờ SC-02 | — |
| SC-04 | Chuẩn hóa và dùng lại khách/xe, dịch vụ | Chờ SC-01 | — |
| SC-05 | Danh sách lịch và check-in online | Chờ SC-02 | — |
| SC-06 | Walk-in nguyên tử | Chờ SC-03 đến SC-05 | — |
| SC-07 | Phiếu, LABOR và tính tiền | Chờ SC-04, SC-05 | — |
| SC-08 | Tích hợp danh mục và nhập tồn của INV-03/05 | Chờ SC-02, SC-03 | — |
| SC-09 | Xuất kho vào phiếu | Chờ SC-07, SC-08 | — |
| SC-10 | Hoàn kho và đổi giá PART | Chờ SC-09 | — |
| SC-11 | Hoàn tất và sinh hóa đơn | Chờ SC-05, SC-07, SC-10 | — |
| SC-12 | Giao diện tiếp nhận | Chờ SC-05, SC-06 và hợp đồng SC-07 | — |
| SC-13 | Giao diện phiếu sửa và hóa đơn | Chờ SC-07 đến SC-12 | — |
| SC-14 | Nghiệm thu SQL Server, quyền và browser | Chờ SC-02 đến SC-13; E2E online cần Guest Booking | — |

## Cập nhật khi kết thúc mỗi subtask

Thêm ngày, người thực hiện, nhánh/mốc code, file đã đổi, quyết định khác kế hoạch, lệnh và kết quả, mã AC được chứng minh, cùng bước tiếp theo. Đánh dấu đạt khi có bằng chứng tương ứng trong [ma trận nghiệm thu](repair-intake-plan.md#5-ma-trận-nghiệm-thu). Ghi riêng kết quả E2E với booking fixture và E2E có booking tạo qua API thật.

| Loại bằng chứng | Lệnh hoặc kịch bản | Kết quả |
|---|---|---|
| Unit/API và quyền | `mvn test`, gồm SecurityFilterChain và CSRF | Chưa chạy |
| Schema SQL Server | `mvn -Psqlserver-it verify`, DB sạch, chạy lại và nâng từ V004 | Chưa chạy |
| Transaction SQL Server | Commit/rollback, replay, đua ISSUE/RETURN và check-in/hoàn tất | Chưa chạy |
| Frontend typecheck/build | `npm run build` | Chưa chạy |
| Browser | Walk-in và online từ API Guest Booking, DB test riêng | Chưa chạy |
