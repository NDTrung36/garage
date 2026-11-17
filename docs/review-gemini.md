# Kết quả kiểm chứng review Gemini

Ngày kiểm chứng: 17/11/2026. Đầu vào: nhận xét Gemini do chủ dự án cung cấp và ba tài liệu trong docs. Đối chiếu thêm tài liệu chính thức SQL Server, Spring Security 6.5 và Java 21; kiểm tra một số mẫu SQL trên instance local.

Phạm vi sửa: [đặc tả](requirements.md), [ERD](erd.md), [kiến trúc](architecture.md). Chưa triển khai code nghiệp vụ hoặc tạo migration.

## 1. Kết luận theo từng nhận xét

| Nội dung review | Kết luận kiểm chứng | Điều chỉnh đã thực hiện |
|---|---|---|
| Phụ tùng bị khóa toàn cục; cách gộp dòng/đơn giá chưa rõ | Đúng về thiếu quy tắc gộp và giá; chưa có cơ sở kết luận khóa toàn kho. Khóa hàng part là cần thiết cho tồn dùng chung | Một dòng PART mỗi part/phiếu, filtered unique index; nhiều lần xuất/hoàn cộng dồn. Đơn giá lấy lần xuất đầu; sửa giá riêng áp dụng toàn dòng, có lý do/audit |
| UPDLOCK/HOLDLOCK trên khóa chưa tồn tại gây shared-range conversion deadlock; đổi HOLDLOCK sang SERIALIZABLE | Không chấp nhận cách giải thích và cách thay hint như đề xuất. Hai hint tương đương; mẫu kiểm tra thực tế cho cùng update-range lock | Giữ UPDLOCK/HOLDLOCK, làm rõ transaction, index equality, chờ commit/rollback, timeout và retry. Không dùng MERGE hoặc XLOCK mặc định |
| ERD thiếu cột so với từ điển | Đúng; hình trước đây là bản rút gọn, không đủ làm checklist migration | Bổ sung đầy đủ cột, timestamps, version, snapshot và FK người thao tác; kiểm tra tự động khớp 17 bảng, 162 cột |
| Có thể đặt trùng xe cùng slot với key khác | Đúng; idempotency chỉ bảo vệ cùng key | Thêm snapshot biển số chuẩn hóa và filtered unique slot + biển số. Kiểm tra cả CONFIRMED/CHECKED_IN/COMPLETED để khớp quy tắc giữ lượt, kể cả đổi SĐT |
| OPEN thừa hoặc gây kẹt thao tác | Không phải lỗi bắt buộc nếu có use case start, nhưng thêm bước không cần cho MVP | Bỏ OPEN và endpoint start; check-in/walk-in tạo phiếu IN_PROGRESS; LABOR/PART chỉ sửa trước COMPLETED |
| HttpSession không tự hỗ trợ tìm và hủy mọi session theo user | Đúng; thiết kế cũ thiếu cơ chế cụ thể | Thêm auth_version và filter đọc active/role/version từ DB trước khi cấp quyền request nội bộ; session cũ bị từ chối từ request kế tiếp |
| Tra cứu bằng SĐT công khai làm lộ lịch sử và chi phí | Đúng về mức truy cập; đây là lựa chọn đã được chủ dự án xác nhận | Giữ SĐT hoặc mã booking độc lập, không thêm OTP/biển số bắt buộc. Tiếp tục DTO allowlist, che liên hệ, no-store và rate limit; không gọi đây là xác thực |
| GET availability vừa đọc vừa sinh slot | Đúng; thiết kế cũ có đường ghi trong GET và khóa cấu hình | Sinh sẵn khi startup, 00:05 mỗi ngày và sau thay đổi cấu hình. GET chỉ đọc; generated_through phân biệt chưa sinh lịch với ngày đóng cửa |
| Cần thống nhất cách làm tròn | Đúng; đặc tả cũ chưa chọn RoundingMode | Chọn HALF_UP về VND trên từng dòng rồi cộng. Dùng BigDecimal và đối chiếu ROUND với DECIMAL |
| Modular monolith và thứ tự khóa loại bỏ hoàn toàn phụ thuộc vòng/deadlock | Đánh giá quá chắc chắn. Ranh giới module là quy tắc cần được tuân thủ; thứ tự khóa giảm rủi ro nhưng không chứng minh toàn bộ hệ thống hết deadlock | Không diễn đạt như bảo đảm tuyệt đối; bổ sung regression cạnh tranh và sửa thứ tự khóa check-in/hoàn tất như phần 2 |

## 2. Phát hiện bổ sung: check-in lặp và hoàn tất sửa

Thiết kế trước có hai thứ tự:

- Check-in lặp: giữ booking, sau đó đọc repair order đã có.
- Hoàn tất: giữ repair order, sau đó cập nhật booking.

Nếu hai request xen kẽ, mỗi bên có thể chờ khóa bên còn lại. Đây là vòng chờ suy ra từ luồng thiết kế, chưa phải lỗi đã tái hiện bằng code ứng dụng vì các use case chưa được triển khai.

Đã đổi hoàn tất thành **booking → repair order → invoice**. Check-in giữ **slot → booking → repair order**; các lệnh LABOR/PART chỉ khóa repair order và không lấy khóa booking sau đó. Thêm tiêu chí AC-24 cho kịch bản này.

## 3. Bằng chứng kiểm tra

### 3.1 Kiểm tra thực tế SQL Server

Môi trường: SQL Server Express local, phiên bản `16.0.1000.6` (SQL Server 2022). Dùng Windows Authentication; toàn bộ bảng kiểm tra nằm trong tempdb dưới dạng bảng tạm, được giải phóng khi đóng kết nối. Không sửa database garage hoặc database nghiệp vụ khác.

| Kiểm tra | Kết quả |
|---|---|
| Unique PART khi part_id khác null, nhiều dòng LABOR part null | Đúng: dòng PART trùng bị chặn, nhiều dòng null được phép |
| Filtered unique booking dùng IN với ba trạng thái giữ lượt | Tạo index thành công; trùng booking active bị chặn |
| Booking COMPLETED vẫn bảo vệ tính unique | Đúng; chuyển sang COMPLETED không cho đặt lại cùng biển số/slot |
| CANCELLED/NO_SHOW, slot null của walk-in, slot hoặc biển số khác | Cho phép đúng phạm vi; booking đã hủy có thể được thay bằng booking mới |
| Tính tiền bằng SQL DECIMAL và ROUND | `0.500 × 101` thành 51 VND; `1.250 × 155000` thành 193.750 VND |
| Hai transaction đọc cùng khóa chưa tồn tại bằng UPDLOCK/HOLDLOCK | Quan sát RangeS-U; transaction thứ hai chờ, sau đó đọc kết quả transaction đầu vừa commit |
| Lặp cùng phép kiểm tra với UPDLOCK/SERIALIZABLE | Cùng RangeS-U và hành vi chờ/đọc như trên |

Kết quả khóa phù hợp tài liệu Microsoft: HOLDLOCK tương đương SERIALIZABLE; update scan dưới serializable dùng RangeS-U. Phép kiểm tra này không chứng minh mọi execution plan, trạng thái dữ liệu và giao dịch tương lai đều không deadlock. [Table hints](https://learn.microsoft.com/en-us/sql/t-sql/queries/hints-transact-sql-table), [Key-range locks](https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide).

### 3.2 Kiểm tra tài liệu

- Đối chiếu danh sách cột Mermaid với từ điển: khớp 17 bảng, 162 cột.
- Kiểm tra liên kết file local, code fence, JSON mẫu và Unicode NFC.
- Kiểm tra chéo quy tắc trạng thái, giá/rounding, public lookup, auth_version, slot generation và thứ tự khóa.
- Validator tiếng Việt chạy ở chế độ strict, không có lỗi hoặc cảnh báo.

Chưa chạy integration test ứng dụng, migration Flyway, xác thực/session hoặc E2E; source nghiệp vụ chưa tồn tại. Chưa render trực quan các sơ đồ Mermaid trong lần review này. Các trường hợp rollback request đầu, deadlock/retry và scheduler lỗi đã được bổ sung vào tiêu chí kiểm thử khi triển khai.

## 4. Căn cứ và lựa chọn triển khai

SQL Server cho phép IN với hằng và IS NOT NULL trong filtered index; cần đúng SET option trên kết nối tạo/ghi dữ liệu. Hai mẫu index đã được chạy thử trên bảng tạm. [Microsoft — CREATE INDEX](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-index-transact-sql).

SQL ROUND làm tròn điểm giữa ra xa 0. Với tiền không âm của MVP, chọn Java HALF_UP cho cùng kết quả; luôn tính trên DECIMAL/BigDecimal và làm tròn từng dòng. [Microsoft — ROUND](https://learn.microsoft.com/en-us/sql/t-sql/functions/round-transact-sql), [Java 21 — RoundingMode](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/RoundingMode.html).

Spring Security có cơ chế quản lý session và cần cấu hình cụ thể; session trong bộ nhớ không tự tạo một chỉ mục tra cứu theo user. MVP chọn tự kiểm tra auth_version qua filter để tránh thêm kho session chỉ nhằm thu hồi quyền. Không tuyên bố hủy vật lý mọi session ngay tại thời điểm Admin cập nhật. [Spring Security 6.5 — Session management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html).

Quy tắc gộp phụ tùng, giá toàn dòng, hai trạng thái phiếu sửa và tra cứu public là quyết định thiết kế sản phẩm; không phải yêu cầu bắt buộc của SQL Server hoặc Spring Security.
