# Deploy backend lên Render

Tạo **Web Service**, chọn **Language: Docker**. Với repository backend riêng, để Root Directory trống; nếu repository chứa cả mobile/backend, đặt Root Directory là `backend`. Dockerfile Path: `./Dockerfile`, Docker Build Context Directory: `.`, Docker Command để trống. Health Check Path: `/actuator/health/readiness`.

Ứng dụng tự đọc `PORT` do Render cấp và lắng nghe trên mọi interface theo mặc định Spring Boot. `EXPOSE 8080` chỉ mô tả cổng mặc định khi chạy local.

Trong **Environment**, nhập các biến từ `.env.example` với giá trị của môi trường triển khai:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; nếu URL app dùng pooler thì `DB_MIGRATION_URL` phải là direct URL tới cùng database/branch. `DB_POOL_SIZE=5` là mặc định.
- `ASL_CATALOG_CURSOR_SECRET`, `ASL_DICTIONARY_CURSOR_SECRET`, `ASL_SYNC_CURSOR_SECRET`: mỗi biến là secret ngẫu nhiên ít nhất 32 bytes, giữ ổn định qua các lần deploy.
- `MEDIA_PUBLIC_BASE_URL`, `ASL_SUPPORT_URL`, `ASL_SHARE_ORIGIN`: các URL HTTPS thật của CDN/support/backend tương ứng.
- Khi leader cấp Firebase: `FIREBASE_PROJECT_ID`; thêm service-account JSON bằng Render Secret Files, rồi đặt `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/<filename>`. Xem [ACCOUNT_SYNC_SETUP.md](ACCOUNT_SYNC_SETUP.md).

Nếu deploy bản demo dùng dữ liệu media development hiện tại, đặt `SPRING_PROFILES_ACTIVE=dev`, `ASL_MEDIA_DEVELOPMENT_FIXTURES=true` và `ASL_LESSON_PREREQUISITES_ENABLED=false`. Khi triển khai production, bỏ profile dev, đặt media fixtures false và dùng nội dung/media đã xuất bản phù hợp. Render dùng biến `SPRING_PROFILES_ACTIVE`, không dùng khóa properties `spring.profiles.active` khi nhập environment variables.

Flyway tự áp dụng migrations khi app khởi động vào database được cấu hình. Docker build không kết nối DB. Không cần `IMAGEKIT_PRIVATE_KEY` cho API runtime; key đó chỉ phục vụ công cụ upload/audit local. Không đưa `.env` hoặc credential vào image/repository.

Build/chạy local từ `backend/` khi Docker Engine đang chạy:

```sh
docker build -t asl-backend .
docker run --rm -p 8080:8080 --env-file .env asl-backend
```

Image chạy với UID/GID `10001`, Java 21 JRE; Maven chỉ có trong build stage. Build bỏ qua tests; chạy test suite riêng trước deploy. Firebase provider thật chỉ kiểm tra được sau khi có cấu hình từ leader.

Tham khảo: [Docker trên Render](https://render.com/docs/docker), [Web Services và PORT](https://render.com/docs/web-services), [Environment và Secret Files](https://render.com/docs/configure-environment-variables).
