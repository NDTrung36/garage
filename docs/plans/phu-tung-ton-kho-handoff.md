# Bàn giao Phụ tùng và tồn kho

Cập nhật 18/11/2026 · **Đã lập kế hoạch. Chưa bắt đầu triển khai.**

[Kế hoạch, hợp đồng và ca kiểm thử](phu-tung-ton-kho.md) · [Đặc tả](../requirements.md) · [Kiến trúc](../architecture.md) · [ERD](../erd.md)

## Mốc nhận việc

- Kế hoạch dựa trên `main` tại `e815d85` và worktree đang có thay đổi riêng về lịch/dịch vụ. V004 có trong worktree lúc kiểm tra, chưa được Git theo dõi. Trước khi chọn version Flyway, kiểm tra lại `git status --short --branch` và `rg --files backend/src/main/resources/db/migration`. Giữ thay đổi đang làm của người khác.
- Đợt A là danh mục, nhập/điều chỉnh và lịch sử kho. Đợt B dùng ID phiếu thật và khóa phiếu dùng chung cho xuất/hoàn/sửa giá. Code phiếu và bàn giao tích hợp chưa có tại mốc trên. Có thể nhận INV-01 và làm đợt A trước.
- Việc đầu tiên là chốt quyền sở hữu migration `parts → repair_orders/repair_items → stock_movements` với đầu việc phiếu, xác nhận migration chứa `audit_logs` và dùng lại adapter idempotency nếu đã xuất hiện. V005–V007 là dự kiến theo mốc repo hiện tại.
- Ghi các lựa chọn hợp đồng vào tài liệu ở INV-01. Các lựa chọn cần đối chiếu gồm ghi chú nhập kho nullable riêng với lý do, số lượng JSON dạng chuỗi, version danh mục/phiếu và cho phép Admin nhập/điều chỉnh part inactive. Chi tiết nằm trong §3 của kế hoạch.

## Checklist triển khai

| Việc | Phụ thuộc | Trạng thái | Kết quả cần ghi tại bàn giao |
|---|---|---|---|
| [INV-01](phu-tung-ton-kho.md#inv-01--đồng-bộ-hợp-đồng-và-mốc-tích-hợp) Hợp đồng và mốc | Kiểm tra nhánh tích hợp | Chưa làm | Mốc Git, owner schema, migration kế tiếp, hợp đồng phiếu và các lựa chọn đã chốt |
| [INV-02](phu-tung-ton-kho.md#inv-02--migration-danh-mục-phụ-thuộc-phiếu-và-ledger) Flyway | INV-01 | Chưa làm | Version thực, DB test, schema đích, kết quả DB sạch/chạy lại/nâng cấp |
| [INV-03](phu-tung-ton-kho.md#inv-03--danh-mục-phụ-tùng-và-quyền-ghi) Danh mục | INV-02 | Chưa làm | Endpoint đã có, test version và khóa đổi đơn vị |
| [INV-04](phu-tung-ton-kho.md#inv-04--idempotency-và-chờ-khóa-cho-lệnh-kho) Idempotency | INV-01, V002 | Chưa làm | Adapter đã dùng, kết quả cùng key khi chờ/commit/rollback, timeout và cleanup |
| [INV-05](phu-tung-ton-kho.md#inv-05--số-dư-nhập-điều-chỉnh-và-lịch-sử) Kho độc lập | INV-02/03/04 | Chưa làm | Kết quả nhập/điều chỉnh, lịch sử, số dư và ledger sau rollback |
| [INV-06](phu-tung-ton-kho.md#inv-06--xuất-hoàn-và-sửa-giá-trên-phiếu) Kho trên phiếu | INV-05 và hợp đồng phiếu | Chưa làm | Entry point/khóa phiếu thực, kết quả race xuất/hoàn/chốt, snapshot, audit |
| [INV-07](phu-tung-ton-kho.md#inv-07--kiểm-tra-hợp-đồng-api-role-csrf-và-phiên) API và phân quyền | Kiểm đợt A sau INV-05, đợt B sau INV-06 | Chưa làm | Ma trận Guest/Staff/Admin, CSRF, hai phiên bị thu hồi |
| [INV-08](phu-tung-ton-kho.md#inv-08--giao-diện-danh-mục-tồn-và-lịch-sử) UI kho | INV-03/05 và kiểm quyền đợt A | Chưa làm | Build, browser Admin/Staff, retry sau mất response |
| [INV-09](phu-tung-ton-kho.md#inv-09--giao-diện-phụ-tùng-trên-phiếu) UI phiếu | INV-06/08, kiểm quyền đợt B và trang phiếu | Chưa làm | Build, browser trên phiếu tạo thật, dữ liệu sau hoàn tất |
| [INV-10](phu-tung-ton-kho.md#inv-10--chốt-nghiệm-thu-và-bàn-giao) Nghiệm thu | Khi từng đợt đủ điều kiện | Chưa làm | Gắn evidence cho từng dòng ma trận trong kế hoạch và ghi phần phụ thuộc còn thiếu |

## Nhật ký bằng chứng

| Hạng mục | Phạm vi | Trạng thái |
|---|---|---|
| Rà repo | Git/worktree, đặc tả, kiến trúc, ERD, migrations, SQL harness, Spring Security, API client, UI shell | Đã rà để lập kế hoạch |
| Build và unit | `mvn test`, `mvn -DskipTests package`, `npm run build` | Chưa chạy cho feature |
| API và security | DTO, lỗi, Guest/Staff/Admin, CSRF, phiên | Chưa chạy |
| SQL Server | Flyway sạch/chạy lại/nâng cấp, rollback, race và idempotency | Chưa chạy |
| Browser đợt A | Admin/Staff, danh mục, nhập/điều chỉnh, lịch sử và retry | Chưa chạy |
| Browser đợt B | Tạo phiếu thật, xuất/hoàn/sửa giá/chốt | Chưa chạy. Cần luồng phiếu tích hợp |
| Liên kết ngoài feature | Đối chiếu hóa đơn/public lookup, AC-16 phần restore | Chưa có bằng chứng trong bàn giao kho |

Khi cập nhật một dòng, ghi ngày, nhánh hoặc commit đang kiểm, lệnh và DB/môi trường test, ID ca kiểm thử, kết quả thực tế và vị trí bằng chứng. Dùng dữ liệu tổng hợp. Nếu bị chặn, ghi phụ thuộc cụ thể, người/đầu việc cần phối hợp và phép kiểm còn thiếu.
