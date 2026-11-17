# Kế hoạch đăng nhập và phân quyền nhân viên

Ngày lập: 17/11/2026. Trạng thái: đã triển khai phiên bản đầu; còn thiếu kiểm thử API/SQL Server/browser và hardening theo môi trường deploy.

Tham chiếu: [đặc tả](requirements.md), [kiến trúc](architecture.md), [schema users](erd.md#31-identity-users).

## Kết quả cần đạt

Admin và Staff đăng nhập được bằng tài khoản do garage quản lý. Backend kiểm tra quyền trên từng request nội bộ. Admin quản lý tài khoản; khóa/mở khóa, đổi role hoặc reset mật khẩu làm phiên cũ mất quyền từ request tiếp theo sau commit. Giao diện thể hiện đúng quyền và xử lý phiên hết hạn.

Đây là feature gồm cả backend và frontend. Bảng users đã có trong V001; không cần migration mới cho phạm vi dưới đây. Giữ nguyên các migration đã áp dụng. Không triển khai nghiệp vụ booking, khách hàng, sửa chữa, kho hay hóa đơn trong feature này.

## Quyết định thiết kế

Những quyết định đã có trong đặc tả:

- Hai role ADMIN và STAFF; Admin có quyền Staff. Customer độc lập với User, không có đăng ký tài khoản công khai.
- Session phía server, một backend instance, frontend/API cùng origin. Restart backend yêu cầu đăng nhập lại.
- Principal chứa userId, role và authVersion; đọc trạng thái user từ SQL Server trước khi cấp quyền cho từng request nội bộ.
- Không dùng JWT, refresh token, Redis, bảng session, OAuth, MFA hoặc tài khoản khách hàng.
- Login/logout và các lệnh nội bộ dùng CSRF; chỉ miễn CSRF đúng method/path của các POST public đã được đặc tả.

Các lựa chọn đề xuất cho kế hoạch này, áp dụng khi triển khai nếu không có điều chỉnh:

| Nội dung | Quy tắc |
|---|---|
| Username | Trim rồi lowercase Locale.ROOT; chỉ ASCII a-z, 0-9, dấu chấm, gạch dưới, gạch ngang; dài 3–100; bất biến sau tạo |
| Tên hiển thị | Trim, dài 1–100 ký tự, cho phép tiếng Việt |
| Mật khẩu | Ít nhất 12 ký tự, tối đa 72 byte UTF-8; không trim/đổi chữ; BCrypt cost 12; không log hoặc trả hash |
| Session | Timeout sau 30 phút không hoạt động; cho phép nhiều phiên; không remember-me |
| Cookie | HttpOnly, SameSite=Lax, Path=/; Secure ở production HTTPS, tắt Secure ở local HTTP; không encode session ID trong URL |
| CSRF | HttpSessionCsrfTokenRepository; GET trả token và headerName; frontend chỉ giữ token trong bộ nhớ, lấy lại sau login/logout |
| API | JSON UTF-8; BIGINT trả chuỗi; timestamp ISO 8601; lỗi theo code/message/fieldErrors/traceId trong kiến trúc |
| Chống dò mật khẩu | Tối đa 10 POST login/IP/phút, bộ nhớ có TTL và giới hạn số entry; vượt ngưỡng trả 429 và Retry-After; không khóa tài khoản tự động |
| IP sau proxy | Chỉ tin forwarded headers từ reverse proxy đã cấu hình; không tin X-Forwarded-For tùy ý từ client |
| Admin cuối cùng | Cấm khóa/hạ role Admin đang hoạt động cuối cùng; Admin không tự khóa hoặc tự hạ role |
| Đổi mật khẩu | Admin đặt mật khẩu ban đầu/reset trực tiếp; chưa có quên mật khẩu qua email, tự đổi mật khẩu hoặc bắt buộc đổi ở lần đăng nhập đầu |
| Audit trong feature | Log có cấu trúc: actorId, targetId, action, outcome, timestamp, traceId; không ghi credentials/token/cookie; bảng audit và giao diện audit làm sau |

Giới hạn bảo đảm: request đã được cấp quyền trước khi thay đổi tài khoản commit có thể hoàn tất. Không tuyên bố ngắt request đang chạy. Khi DB lỗi, request nội bộ trả 503 và không được cấp quyền từ dữ liệu phiên cũ.

## Hợp đồng API cần chốt ở ID-01

Các đường dẫn dưới đây có prefix /api.

| Method/path | Input và quyền | Kết quả chính |
|---|---|---|
| GET /auth/csrf | Anonymous hoặc đã đăng nhập | 200 {token, headerName}; tạo session khi cần; no-store |
| POST /auth/login | JSON {username,password}, header CSRF hợp lệ | 200 user DTO và cookie; sai tài khoản/mật khẩu hoặc bị khóa trả chung 401 INVALID_CREDENTIALS |
| GET /auth/me | Session hợp lệ, kiểm tra DB | 200 {id,username,displayName,role}; thiếu/hết hạn/bị thu hồi trả 401 |
| POST /auth/logout | CSRF hợp lệ; cho phép gọi khi không còn authentication | 204; hủy session, xóa cookie; gọi lại với token mới vẫn 204 |
| GET /admin/users | ADMIN; page mặc định 0, size mặc định 20, tối đa 100 | 200 page DTO, sắp xếp id tăng; có active/authVersion, không có hash |
| POST /admin/users | ADMIN; {username,displayName,role,password} | 201 DTO; active=true; username trùng trả 409 USERNAME_EXISTS |
| PATCH /admin/users/{id} | ADMIN; expectedAuthVersion và ít nhất một trong displayName/role/active | 200 DTO; version bảo mật cũ trả 409 STALE_AUTH_VERSION |
| POST /admin/users/{id}/reset-password | ADMIN; {password,expectedAuthVersion} | 204; version cũ trả 409 STALE_AUTH_VERSION |

Reset-password tách thành command riêng thay vì trộn password vào PATCH như API dự kiến trước đây. ID-01 cập nhật kiến trúc theo hợp đồng này trước khi code. POST tạo/reset không tự retry trên frontend sau timeout; tải lại trạng thái hoặc thực hiện lại có chủ đích.

GET /auth/me và các response quản lý user dùng Cache-Control: no-store. API không redirect HTML sang trang login. Thiếu authentication trả 401; đã đăng nhập nhưng thiếu role trả 403 FORBIDDEN; thiếu/sai CSRF trả 403 CSRF_INVALID; input sai trả 400; không có user đích trả 404; vi phạm bảo vệ Admin trả 409 LAST_ACTIVE_ADMIN hoặc SELF_ACCOUNT_CHANGE_FORBIDDEN. Request sai cả CSRF và authentication có thể được CSRF filter từ chối 403 trước; frontend phân biệt bằng code.

## ID-01 — Chốt hợp đồng và ma trận quyền

1. **Làm cái gì:** cập nhật requirements/architecture theo API, validation, lỗi và quyết định trong tài liệu này; liệt kê chính xác từng method/path public, auth, staff, admin và quyền tương ứng. Xác định model frontend và các tình huống hết hạn/thu hồi phiên.
2. **Không làm cái gì:** không code use case; không mở rộng permission động, nhiều garage hoặc role mới; không thay đổi quy tắc guest booking/lookup public.
3. **Tuân thủ luật gì:** Admin có quyền Staff; Customer/User tách biệt; quyền backend là quyết định cuối; mọi lựa chọn mới phải xuất hiện trong đặc tả, không chỉ nằm trong UI hoặc test.
4. **Kiểm chứng bằng cách nào:** đối chiếu từng endpoint với bảng quyền và danh sách lỗi; kiểm tra các ví dụ JSON, độ dài dữ liệu với V001; rà soát chéo ba tài liệu để không còn hợp đồng mâu thuẫn.
5. **Khi nào thì dừng:** API, permission matrix, validation, cookie/session/CSRF, bootstrap và các quyết định mới có một mô tả thống nhất; không còn lựa chọn ảnh hưởng implementation bị bỏ ngỏ.

Đầu ra: cập nhật docs/requirements.md, docs/architecture.md và tài liệu này. Phụ thuộc: không.

## ID-02 — User model, persistence và mật khẩu

1. **Làm cái gì:** triển khai domain User/Role và repository port; JPA mapping/adapter cho users; chuẩn hóa username, kiểm tra dữ liệu và hash/match mật khẩu; mapper DTO không có hash. Cập nhật updated_at khi ghi.
2. **Không làm cái gì:** không tạo users mới bằng migration; không mapping các bảng customer/catalog/repair; không thêm Customer Authentication; không xuất JPA entity ra API.
3. **Tuân thủ luật gì:** api → application → domain; infrastructure triển khai port; domain không import Spring/JPA. DB unique username là bảo vệ cuối; không bắt lỗi duplicate rồi tiếp tục transaction đã rollback-only. BCrypt không được cắt cụt mật khẩu quá giới hạn byte. Chuyển ddl-auto=validate khi mapping users có mặt; Flyway vẫn quản lý schema.
4. **Kiểm chứng bằng cách nào:** unit test chuẩn hóa username, mật khẩu có tiếng Việt và ranh giới 72 byte; hash cùng password cho salt khác nhau nhưng match đúng. Integration test SQL Server cho mapping, unique username, timestamp và đọc/ghi auth_version; kiểm tra response không có hash.
5. **Khi nào thì dừng:** user đọc/ghi đúng schema V001 trên SQL Server; input không hợp lệ bị chặn; mật khẩu chỉ tồn tại dưới dạng hash trong DB; không cần sửa migration nền.

Đầu ra: backend identity/domain, application và infrastructure. Phụ thuộc: ID-01.

## ID-03 — Bootstrap Admin đầu tiên

1. **Làm cái gì:** thêm chế độ bootstrap bật rõ ràng bằng biến môi trường; nhận username/displayName/password; chỉ tạo một ADMIN active khi bảng users rỗng; hash trước khi lưu. Ghi hướng dẫn tạo Admin và gỡ biến bootstrap sau lần đầu.
2. **Không làm cái gì:** không seed mật khẩu mặc định; không tạo Admin mỗi startup; không reset tài khoản có sẵn; không cung cấp endpoint bootstrap công khai hoặc tự sửa dữ liệu khi mất quyền Admin.
3. **Tuân thủ luật gì:** bootstrap disabled mặc định; enabled mà thiếu/invalid cấu hình thì fail startup trước khi ghi. Kiểm tra bảng rỗng và INSERT trong một transaction có khóa bảo vệ phạm vi users; kiểm tra lại dưới khóa. Bảng không rỗng thì không thay đổi, kể cả khi không còn ADMIN; không log password.
4. **Kiểm chứng bằng cách nào:** trên DB test: users rỗng tạo đúng một Admin, chạy lại không đổi hash/role/version; disabled không tạo; cấu hình lỗi không ghi; hai bootstrap đồng thời chỉ tạo một tài khoản, lần còn lại bỏ qua an toàn.
5. **Khi nào thì dừng:** chạy bootstrap nhiều lần vẫn chỉ có một Admin ban đầu, tài khoản đăng nhập được sau ID-04; README/.env.example có hướng dẫn và không chứa secret thật.

Đầu ra: bootstrap runner, cấu hình và hướng dẫn vận hành. Phụ thuộc: ID-02; kiểm chứng đăng nhập hoàn tất sau ID-04.

## ID-04 — Login, logout, session và CSRF

1. **Làm cái gì:** triển khai bốn endpoint auth, JSON login qua Spring Security, lưu SecurityContext vào session, đổi session ID sau login, cookie/timeout, CSRF token lifecycle, lỗi JSON và bộ giới hạn login/IP có TTL.
2. **Không làm cái gì:** không dùng JWT/localStorage cho credentials, refresh token, remember-me, SSO, captcha, khóa user tự động; không tắt CSRF toàn ứng dụng hoặc mở CORS credentials cho mọi origin.
3. **Tuân thủ luật gì:** login/logout kiểm tra CSRF; dùng AuthenticationManager và hook/session strategy của Spring Security; nếu custom authentication filter phải lưu context và gọi session strategy đúng cơ chế. Username không tồn tại, password sai, user inactive cùng lỗi 401; dùng dummy hash matching cho username không có để giảm chênh lệch thời gian. Không lưu raw password vào principal/session. Token cũ không được tái sử dụng sau login/logout; frontend lấy token mới.
4. **Kiểm chứng bằng cách nào:** API test với cookie thật qua nhiều request: lấy token → login → me → logout; kiểm tra session ID đổi, session sau login được lưu, session cũ không dùng lại, thiếu/sai token trả 403, GET logout không đăng xuất, timeout và cookie flags đúng môi trường. Kiểm tra ngưỡng 429, Retry-After và TTL bằng clock kiểm soát được.
5. **Khi nào thì dừng:** hai role đăng nhập/đăng xuất được, lỗi có hợp đồng thống nhất, không có HTML redirect, vòng đời session/CSRF và giới hạn login đạt test; chưa tuyên bố hoàn thành thu hồi phiên trước ID-05.

Đầu ra: identity/api và infrastructure/security, cấu hình local/production. Phụ thuộc: ID-01, ID-02.

## ID-05 — Phân quyền và thu hồi phiên

1. **Làm cái gì:** cấu hình quyền theo method/path; STAFF và ADMIN được /staff/**, chỉ ADMIN được /admin/**; auth/me yêu cầu phiên hợp lệ. Thêm kiểm tra DB sau khi tải authentication và trước khi cấp quyền; đối chiếu active, role, auth_version với principal. Xóa authentication/hủy phiên lỗi; DB lỗi trả 503.
2. **Không làm cái gì:** không xây permission engine, cache quyền qua nhiều request, session registry/Redis; không triển khai API nghiệp vụ để làm mẫu; không cho phép toàn bộ /public/** hoặc /auth/** bằng wildcard thiếu kiểm soát.
3. **Tuân thủ luật gì:** allowlist đúng endpoint public; route không được khai báo bị từ chối mặc định. Method security bảo vệ application use case quản trị đã có. Sau commit đổi quyền/khóa/reset, mọi phiên cũ bị từ chối ở request nội bộ kế tiếp; unlock không hồi sinh phiên. Chỉ miễn CSRF đúng các POST public đã chốt. Không tự tăng quyền của session bằng cách cập nhật role mới vào principal cũ.
4. **Kiểm chứng bằng cách nào:** ma trận Guest/Staff/Admin × auth/staff/admin/public; dùng test-only controller để kiểm tra matcher tương lai, không thêm endpoint mẫu vào production. Với hai cookie độc lập của cùng user, cập nhật DB rồi xác minh cả hai bị từ chối; unlock vẫn từ chối; login mới dùng quyền mới. Mô phỏng DB lỗi để xác nhận không cho request qua bằng session cũ.
5. **Khi nào thì dừng:** không có trường hợp gọi trực tiếp backend vượt quyền; AC-21 về hai session cũ đạt; public đã khai báo vẫn không cần login; chưa có quyền ngầm trên endpoint mới.

Đầu ra: SecurityFilterChain, guard thu hồi phiên và kiểm thử ma trận quyền. Phụ thuộc: ID-04.

## ID-06 — Admin quản lý tài khoản

1. **Làm cái gì:** list/create/update/reset-password theo hợp đồng; update tên/role/active; tăng auth_version khi active/role thực sự thay đổi và mỗi lần reset thành công; log tác vụ có cấu trúc. Tạo tài khoản được phép chọn STAFF hoặc ADMIN.
2. **Không làm cái gì:** không DELETE user, đổi username, import hàng loạt, tự đăng ký hoặc gửi password qua email/SMS; không thêm bảng/giao diện audit trong feature này.
3. **Tuân thủ luật gì:** chỉ ADMIN; kiểm tra lại actor đang active/role/version trong transaction ghi. Tất cả thao tác tạo user và thay đổi bảo mật lấy khóa users theo cùng thứ tự trước khi kiểm tra Admin cuối; giữ khóa đến commit để hai Admin không đồng thời loại bỏ nhau. expectedAuthVersion phải khớp hàng đã khóa. Đổi tên không tăng auth_version; role/active không đổi không tăng; reset luôn tăng. Khóa/mở khóa đều tăng. Không tự khóa/hạ role; không làm mất Admin active cuối. Reset chính mình được phép nhưng phải đăng nhập lại. Password reset và tăng version commit/rollback cùng nhau.
4. **Kiểm chứng bằng cách nào:** SQL Server integration test cho duplicate username, version cũ, no-op, displayName, rollback reset; chạy hai transaction khóa/hạ quyền Admin đồng thời và xác nhận còn Admin active. API test Staff bị 403, DTO không có hash. Kiểm tra hai phiên cũ sau từng lệnh khóa/mở khóa/đổi role/reset, bao gồm reset chính Admin đang thao tác.
5. **Khi nào thì dừng:** mọi command đúng quyền và transaction; không thể mất Admin cuối do race; request không hợp lệ không thay đổi dữ liệu; các phiên cũ bị vô hiệu đúng quy tắc.

Đầu ra: use case/controller quản lý user, repository locking và log. Phụ thuộc: ID-02, ID-05.

## ID-07 — Frontend đăng nhập và phiên làm việc

1. **Làm cái gì:** features/auth, trang login, AuthProvider, me khi khởi động, logout, trạng thái loading/authenticated/anonymous; src/api gửi cookie và CSRF. Protected route/layout nhân viên, menu theo role; xử lý 401, 403 CSRF/quyền, 429 và 503 khác nhau.
2. **Không làm cái gì:** không lưu password/session ID/CSRF token vào localStorage; không coi ẩn nút là kiểm soát quyền; không làm dashboard/screen nghiệp vụ; không tự retry lệnh ghi sau timeout hoặc CSRF lỗi.
3. **Tuân thủ luật gì:** tải CSRF trước login và lấy lại sau login/logout; chỉ xóa trạng thái login khi xác định 401, không đăng xuất người dùng chỉ vì 403 hoặc lỗi mạng. 503 hiển thị lỗi/tùy chọn thử lại; 429 dùng Retry-After. Khi phiên mất hiệu lực, xóa dữ liệu nội bộ đang giữ; chỉ redirect về đường dẫn nội bộ hợp lệ. Refresh trang phải kiểm tra me trước khi hiển thị nội dung bảo vệ. Nhiều tab đồng bộ UI ở request kế tiếp, không yêu cầu realtime.
4. **Kiểm chứng bằng cách nào:** browser flow login → refresh → logout → Back; hết phiên khi đang mở trang; server restart; đổi role từ phiên khác; thiếu CSRF; mất mạng/DB. Kiểm tra DevTools không có secret trong storage/log và API bị từ chối khi gọi trực tiếp không có quyền.
5. **Khi nào thì dừng:** Admin/Staff đi hết vòng đời phiên từ trình duyệt, không nháy nội dung nội bộ trước me; lỗi quyền và lỗi hệ thống có cách xử lý riêng, không retry command gây tác dụng phụ.

Đầu ra: frontend/src/features/auth, src/api và routing tối thiểu. Phụ thuộc: ID-04, ID-05. Chỉ thêm router nếu cần cho các trang thực tế; không thêm state framework chỉ cho feature này.

## ID-08 — Frontend quản lý nhân viên

1. **Làm cái gì:** trang Admin danh sách tài khoản có phân trang; tạo user, sửa tên/role, khóa/mở khóa, reset password; hiển thị lỗi validation/username trùng/version cũ; xác nhận tác vụ ảnh hưởng quyền.
2. **Không làm cái gì:** không thiết kế bộ UI chung cho toàn garage, phân quyền bằng checkbox động, màn hình audit hoặc hiển thị lại password sau khi lưu.
3. **Tuân thủ luật gì:** chỉ Admin thấy và vào trang; gửi expectedAuthVersion hiện tại; stale version phải tải lại, không ghi đè tự động. Không gửi form password vào analytics/log; xóa trường password sau thành công/đóng form. UI ngăn tự khóa/hạ role để tránh thao tác nhầm, backend vẫn kiểm tra độc lập. Reset chính mình thành công chuyển về login.
4. **Kiểm chứng bằng cách nào:** browser với Admin tạo Staff → Staff đăng nhập → Admin đổi quyền/khóa/reset → Staff bị yêu cầu đăng nhập lại. Dùng hai Admin kiểm tra màn hình cũ gặp conflict; thử gọi URL trực tiếp bằng Staff và gửi lệnh cấm từ DevTools.
5. **Khi nào thì dừng:** bốn nhóm thao tác list/create/update/reset thực hiện được qua UI; không vượt quyền bằng URL hoặc API; các lỗi conflict và mất phiên không làm mất thông tin về kết quả thao tác.

Đầu ra: frontend/src/features/user và liên kết menu Admin. Phụ thuộc: ID-06, ID-07.

## ID-09 — Nghiệm thu và tài liệu vận hành

1. **Làm cái gì:** chạy bộ kiểm thử liên quan, Maven build/integration profile và frontend typecheck/build; ghi bằng chứng các luồng nghiệm thu; cập nhật README/.env.example và trạng thái tài liệu. Ghi cách bootstrap, HTTPS/cookie/proxy, timeout, giới hạn login, restart và khôi phục quyền Admin qua quy trình vận hành có kiểm soát.
2. **Không làm cái gì:** không deploy production, thay TCP/Windows Authentication/firewall, sửa database dùng thật, thêm benchmark hoặc chạy lại vô hạn các test đã đạt; không tạo nút khôi phục Admin bỏ qua authentication.
3. **Tuân thủ luật gì:** SQL Server test riêng có tên được bảo vệ theo harness hiện có; dữ liệu tổng hợp và cleanup sau chạy; không dùng H2 để kết luận về lock/race SQL Server. Các test identity bổ sung cùng quy tắc an toàn, không làm hỏng migration test hiện tại. Fixture/probe chỉ tồn tại trong test; không lộ endpoint test ở bản build production.
4. **Kiểm chứng bằng cách nào:** lưu báo cáo test và kết quả browser cho: bootstrap lặp; hai role; CSRF; session fixation/timeout/logout; gọi API vượt quyền; hai phiên bị thu hồi; unlock không hồi sinh; race Admin cuối; DB lỗi 503; login 429; public không cần đăng nhập; không có hash/credentials trong response/log. Kiểm chứng public tương lai bằng fixture hoặc security test, không khẳng định booking đã chạy thật.
5. **Khi nào thì dừng:** tất cả tiêu chí trên đạt, build pass, không còn lỗi chặn login/quyền/thu hồi phiên, docs khớp behavior và DB test được dọn. Ghi rõ giới hạn audit bằng log, session một instance và recovery vận hành; không coi đó là chức năng đã triển khai ngoài phạm vi.

Đầu ra: bằng chứng nghiệm thu và hướng dẫn chạy feature. Hiện mới có 2 unit test chuẩn hóa username; các kịch bản API, SQL Server và browser trong subtask này vẫn là việc cần hoàn tất. Phụ thuộc: ID-03 đến ID-08.

## Thứ tự thực hiện và mốc bàn giao

ID-01 → ID-02 → ID-03/ID-04 → ID-05 → ID-06/ID-07 → ID-08 → ID-09.

- Mốc backend auth: ID-01 đến ID-05; có thể kiểm chứng login, CSRF, quyền và thu hồi phiên bằng API/test.
- Mốc quản lý tài khoản: ID-06; có đường thao tác thực tế để thay đổi trạng thái/quyền/mật khẩu.
- Mốc feature hoàn chỉnh: ID-07 đến ID-09; dùng được qua trình duyệt và có bằng chứng nghiệm thu.

Chỉ đánh dấu toàn feature hoàn thành ở mốc cuối. Các task song song nêu trên là quan hệ phụ thuộc, không yêu cầu dùng nhiều agent.

## Nguồn kỹ thuật

- [Spring Security 6.5 — Authentication Persistence and Session Management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html): lưu SecurityContext và vòng đời phiên khi custom login.
- [Spring Security — CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html): bảo vệ login/logout và lấy CSRF token mới sau authentication/logout; implementation phải đối chiếu API của dependency 6.5 thực tế trong Maven.
