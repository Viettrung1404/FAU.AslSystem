# Nền móng backend ASL

Java 21, Spring Boot 4.1.1, PostgreSQL trên Neon; chạy bằng Maven Wrapper, không cần Docker. Chưa triển khai 35 operations nghiệp vụ; xem [kế hoạch](../plan/README.md) và [ADR-0008](../plan/adr/0008.md).

## Chuẩn bị Neon

Neon host database PostgreSQL. Ứng dụng Spring Boot chạy trên máy phát triển hoặc một dịch vụ chạy Java riêng; chưa deploy backend trong workspace này.

1. Tạo project/database hoặc branch dev trên Neon. Trong **Connect**, chọn đúng branch, database và role, lấy kết nối **direct** (hostname không có `-pooler`).
2. Từ thư mục `backend`, copy `.env.example` thành `.env` nếu chưa có. Điền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` bằng thông tin kết nối thật.
3. `DB_URL` có dạng `jdbc:postgresql://HOST:5432/DATABASE?sslmode=require&channelBinding=require&connectTimeout=30`. Giữ username/password ở hai biến riêng; không copy lệnh `psql` hoặc URL `postgresql://user:password@...` nguyên vào JDBC URL.

Spring được cấu hình đọc `.env` theo định dạng Java properties: `KEY=value`, không có dấu nháy bao ngoài hoặc `export`. Nếu giá trị có backslash, viết `\\`; có thể dùng biến môi trường hệ điều hành để tránh escaping. Biến môi trường ưu tiên hơn file. `.env` và `.env.test` đã được ignore. Khi chạy từ IDE, đặt working directory là `backend`; khi deploy, cấu hình các biến môi trường tương ứng.

Dev dùng direct connection cho cả Hibernate và Flyway, với Hikari pool tối đa 5 connections. Nếu sau này dùng pooled URL cho app, đặt thêm `DB_MIGRATION_URL` là **direct URL của cùng database/branch**. Flyway dùng cùng role/password và URL này; không đi qua pooler. `DB_POOL_SIZE` tùy chỉnh pool của app.

Flyway tự chạy khi khởi động: hiện V1 chỉ tạo schema `asl` và migration history ở `public`, chưa tạo 33 bảng trong bản thiết kế. Dùng database dev mới, role có quyền tạo schema/table; không chạy toàn bộ SQL thiết kế thủ công trước Flyway. Migration bảng sẽ bổ sung theo module. Phiên bản PostgreSQL thực tế do project Neon quyết định, không còn pin image Docker 18.6.

## Chạy bằng Java 21 và Maven Wrapper

Maven được wrapper tải tự động, cần kết nối Maven Central lần đầu. Đặt `JAVA_HOME` tới JDK 21 và thêm `bin` vào PATH của terminal nếu IDE chưa cấu hình.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

Chạy sau khi đã điền `.env`. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` bắt buộc; không tự trỏ về PostgreSQL localhost. Cổng HTTP mặc định 8080, đổi bằng `PORT`. Mở terminal khác để kiểm tra:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

Trong workspace hiện tại đã tải JDK 21 portable vào thư mục `.tools` bị ignore, không thay đổi PATH hệ thống. Có thể dùng từ `backend`:

```powershell
$env:JAVA_HOME = (Resolve-Path '../.tools/jdk21/jdk21.0.12_12').Path
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
```

Thư mục portable này không được phân phối cùng source; máy khác tự cài JDK 21. Đóng gói bằng `.\mvnw.cmd package`, chạy bằng `java -jar target/backend-0.1.0-SNAPSHOT.jar` từ thư mục `backend` để đọc cùng `.env`.

## ORM: Spring Data JPA + Hibernate

`pom.xml` đã có `spring-boot-starter-data-jpa`, Hibernate và PostgreSQL JDBC driver. Chưa có entity/repository nghiệp vụ vì các module chưa triển khai. Giữ bộ này cho ASL:

| Vai trò | Trong dự án Spring Boot |
|---|---|
| Ánh xạ object Java với bảng và thực thi SQL | Hibernate, triển khai chuẩn Jakarta Persistence (JPA) |
| API truy cập dữ liệu, CRUD và query theo method | Spring Data JPA, ví dụ `JpaRepository<LessonEntity, UUID>` |
| Lịch sử tạo/sửa bảng như phần migration trong hệ Prisma | Flyway, các file SQL `V2__...sql`, `V3__...sql` |

Đây là lựa chọn tương ứng về vai trò với ORM + migration bên TypeScript, không phải Prisma với cùng API. CRUD thông thường dùng repository; truy vấn PostgreSQL đặc thù/JSONB hoặc bulk update có thể dùng native SQL/JdbcTemplate trong persistence adapter. Không bắt buộc ánh xạ mọi bảng thành entity ngay bây giờ.

JPA entity/repository nằm ở `adapter.out.persistence`; domain/application giữ Java thuần. Flyway là nguồn thay đổi schema; Hibernate dùng `ddl-auto=validate`, không tự tạo/update bảng. Cách này giữ được các constraint, index, trigger và view trong thiết kế DB.

## Kiểm thử

```powershell
.\mvnw.cmd test
python -m pip install -r tools/requirements.txt
python tools/validate_contract.py --report ../plan/verification/contract-report.json
```

`test`: HTTP, ArchUnit và cấu hình datasource/Flyway, không cần DB. `package` chạy các test này và tạo jar. `package -DskipTests` chỉ đóng gói, không phải nghiệm thu.

Để chạy integration, tạo **branch/database Neon riêng cho test**, copy `.env.test.example` thành `.env.test` và điền `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`, rồi chạy:

```powershell
.\mvnw.cmd verify
```

Profile `integration` chỉ ở test resources, dùng `TEST_DB_*` cho cả datasource và Flyway, không fallback sang `DB_*` của app. Thiếu cấu hình hoặc không kết nối được sẽ làm test thất bại, không tự skip. Test chạy migration thật, kiểm tra chạy lại Flyway, HTTP và health/readiness; test cuối đóng pool của chính tiến trình test để mô phỏng mất DB, không tắt Neon. Không có thao tác drop/clean database. Không dùng chung branch test cho nhiều lần chạy đồng thời. Hiện chưa xác minh kết nối Neon vì chưa có credentials.

Tham khảo: [Neon Java/JDBC](https://neon.com/docs/guides/java), [direct và pooled connections](https://neon.com/docs/connect/connection-pooling), [Spring Data JPA](https://spring.io/projects/spring-data-jpa/), [Spring config import](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## Quy ước kiến trúc

Module nằm tại `com.asl.backend.<module>`: `domain`, `application`, `adapter.in.web`, `adapter.out.persistence`, `configuration`. Chỉ tạo lớp khi có use case; không tạo hàng loạt placeholder.

- `domain`: Java thuần, không phụ thuộc application/framework.
- `application`: use case, port, điều phối nghiệp vụ; API công khai ở `application.api`, outbound ports ở `application.port.out`.
- Module khác chỉ dùng `application.api` (interface và kiểu dữ liệu API); không dùng HTTP DTO hoặc JPA entity của nhau.
- `configuration`: Spring wiring; adapter transaction bao quanh use case, không thêm `@Transactional` vào application.
- `platform`: các adapter dùng chung cho HTTP; không trở thành nơi gom domain nghiệp vụ.
- Flyway giữ history trong `public`; dữ liệu nghiệp vụ ở `asl`. Migration thêm theo module, không sửa migration đã chạy. Hibernate chỉ validate.

Request ID do server sinh UUID, trả ở `X-Request-Id` và error body, dùng trong MDC. Validation payload trả 422; JSON/query sai trả 400. Body có field lạ bị từ chối. Các lỗi HTTP giao thức giữ status tương ứng; lỗi không dự kiến trả 500, không lộ stack trace qua response.

Health/readiness là endpoint vận hành ngoài contract mobile. Liveness không phụ thuộc DB; readiness có DB. Chỉ public health, không expose env/config/metrics. Chưa có auth/business controllers.
