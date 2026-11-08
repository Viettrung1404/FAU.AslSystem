# Reporting, Firebase account và learning sync

Runtime module 06–08 sử dụng PostgreSQL/Flyway hiện tại. Không cần Firebase để học public hoặc gửi report dạng guest. Đăng nhập thật cần cấu hình do leader cấp; không có tài khoản/token giả trong runtime.

## Backend

1. Điền cấu hình DB như README. Khởi động Spring Boot để Flyway áp dụng V4 (Firebase UID/email), V5 (checkpoint đã được chấp nhận không bị ghi đè), V6 (`Config.contractVersion=0.4.0`). Giữ nguyên V1–V3.
2. Đặt `FIREBASE_PROJECT_ID` đúng project mobile. Cấu hình Application Default Credentials của môi trường chạy, hoặc `GOOGLE_APPLICATION_CREDENTIALS` trỏ tới file service-account JSON ngoài source. Spring đọc được đường dẫn này từ `.env`; private key không nằm trong app mobile.
3. Đặt `ASL_SYNC_CURSOR_SECRET` là secret ngẫu nhiên ít nhất 32 byte, giống nhau trên các instance. Không đổi secret tùy tiện vì cursor đã cấp sẽ không xác minh được nữa.
4. Công bố TERMS và PRIVACY thật trong `asl.legal_documents`: `document_type`, `version`, `public_url`, `published_at`. Phiên bản hiện hành là bản đã công bố mới nhất của mỗi type. Không tự đưa link/consent mẫu vào DB thật. Endpoint public trả danh sách hiện hành; bootstrap account mới trả 503 nếu chưa cấu hình đủ tài liệu và 422 nếu chưa đồng ý tài liệu bắt buộc.
5. Bật Google/Apple sign-in trong Firebase và đăng ký ứng dụng Android/iOS theo [hướng dẫn mobile](../mobile/ACCOUNT_SETUP.md). Không cấu hình Firebase Auth emulator trong runtime này; verifier chỉ chấp nhận token thật và kiểm tra revoked/disabled.

Firebase chưa cấu hình: public APIs và legal vẫn hoạt động; đăng nhập cần xác minh trả 503. Token sai định dạng trả 401, không được hạ xuống guest khi client đã gửi credential.

## HTTP và dữ liệu

- `POST /api/v1/reports`: không cần account; có Authorization thì xác minh account. `clientReportId` là UUID ổn định khi retry, body/snapshot giữ nguyên. ID/body/owner giống nhau trả cùng receipt; reuse ID với dữ liệu khác trả 409. Client UUID được dùng làm khóa guest bền vững, không dùng IP làm identity.
- `GET /api/v1/legal-documents`, `POST /api/v1/auth/bootstrap`, `GET/PATCH /api/v1/me`: account gắn với UID đã verify, không email. Bootstrap tạo account/consent/sync stream trong một transaction. Apple relay/null email được hỗ trợ.
- Sync snapshot/changes/mutations và stats yêu cầu account đã bootstrap. Dedupe theo account+mutation ID trước revision; một lỗi làm rollback toàn batch. Client giữ immutable outbox khi timeout, pull theo cursor cũ sau push và không nhập lại dữ liệu account A sang B.
- Cursor có chữ ký và gắn account/watermark; history không tự prune, giữ ít nhất 90 ngày. Snapshot lấy trạng thái mới nhất của từng entity tại watermark cố định; tombstone và false favorites được giữ.
- Tổng điểm/streak/signs đã học lấy từ dữ liệu completion được kiểm tra theo frozen lesson version; không nhận tổng điểm hoặc Premium flag từ client.

Rate limit mặc định theo mỗi instance: identity 60/phút/socket IP, reports 10/phút/socket IP và thêm budget theo UUID khi đăng nhập, sync 120/phút/socket IP. Identity/report có cấu hình `ASL_IDENTITY_RATE_LIMIT_*` và `ASL_REPORTING_RATE_LIMIT_*`. Triển khai nhiều instance cần gateway/shared limiter để có giới hạn chung; không tin X-Forwarded-For từ client làm identity.

Billing thật do leader phụ trách. `CollectionAccess` tiếp tục là điểm tích hợp quyền Premium; cờ UI mua local không cấp entitlement backend.

## Kiểm chứng

`mvnw test` chạy unit/HTTP/architecture. `mvnw verify` cần database test riêng qua `TEST_DB_*`; test identity chỉ thay verifier bên ngoài trong test configuration để kiểm chứng HTTP/DB/concurrency, không có bypass trong app.

`python tools/verify_account_responses.py` đối chiếu response HTTP đã capture với OpenAPI. Xem [báo cáo module 06–08](../.plan/verification/06-08-implementation.md) cho kết quả và phần Firebase/thiết bị thật chưa nghiệm thu.
