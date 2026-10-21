# Nền móng backend ASL

Java 21, Spring Boot 4.1.1, PostgreSQL trên Neon; chạy bằng Maven Wrapper, không cần Docker. Đã triển khai module 01-media (`POST /api/v1/media/urls`); 34 operations còn lại theo [kế hoạch](../plan/README.md). Xem [ADR-0008](../plan/adr/0008.md).

## Chuẩn bị Neon

Neon host database PostgreSQL. Ứng dụng Spring Boot chạy trên máy phát triển hoặc một dịch vụ chạy Java riêng; chưa deploy backend trong workspace này.

1. Tạo project/database hoặc branch dev trên Neon. Trong **Connect**, chọn đúng branch, database và role, lấy kết nối **direct** (hostname không có `-pooler`).
2. Từ thư mục `backend`, copy `.env.example` thành `.env` nếu chưa có. Điền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` bằng thông tin kết nối thật.
3. `DB_URL` có dạng `jdbc:postgresql://HOST:5432/DATABASE?sslmode=require&channelBinding=require&connectTimeout=30`. Giữ username/password ở hai biến riêng; không copy lệnh `psql` hoặc URL `postgresql://user:password@...` nguyên vào JDBC URL.

Spring được cấu hình đọc `.env` theo định dạng Java properties: `KEY=value`, không có dấu nháy bao ngoài hoặc `export`. Nếu giá trị có backslash, viết `\\`; có thể dùng biến môi trường hệ điều hành để tránh escaping. Biến môi trường ưu tiên hơn file. `.env` và `.env.test` đã được ignore. Khi chạy từ IDE, đặt working directory là `backend`; khi deploy, cấu hình các biến môi trường tương ứng.

Dev dùng direct connection cho cả Hibernate và Flyway, với Hikari pool tối đa 5 connections. Nếu sau này dùng pooled URL cho app, đặt thêm `DB_MIGRATION_URL` là **direct URL của cùng database/branch**. Flyway dùng cùng role/password và URL này; không đi qua pooler. `DB_POOL_SIZE` tùy chỉnh pool của app.

Flyway tự chạy khi khởi động: V1 tạo schema `asl`; V2 tạo toàn bộ **33 bảng, 6 view, 17 function và 35 trigger**, cùng hai dòng cấu hình mobile/scoring. History ở `public`. V1/V2 đã áp dụng thành công lên Neon PostgreSQL 18.6 ngày 18/10/2026; chưa seed nội dung học. Xem [ADR-0009](../plan/adr/0009.md) và [đối chiếu SQL/contract](../db-design/ASL_Database_Contract_Review_mobile_v0_2.md).

Với database mới, role cần quyền tạo schema/table và extension `pg_trgm`. Không chạy SQL thiết kế thủ công trước/sau Flyway. Không sửa V1/V2 đã áp dụng; mọi thay đổi tiếp theo dùng V3 trở đi. Các module dùng bảng sẵn có, triển khai entity/repository/API khi đến lượt.

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

## Xuất schema thật để chia sẻ trong nhóm

File `schema.sql` trong thư mục `backend` là snapshot cấu trúc lấy trực tiếp từ database được cấu hình trong `.env`: schema `asl` và `public`, gồm bảng, view, function, trigger, constraint, index và extension `pg_trgm` dùng cho tìm kiếm. Không xuất dữ liệu bảng, mật khẩu, owner hoặc quyền của tài khoản nguồn. Cấu trúc bảng `public.flyway_schema_history` có trong snapshot, nhưng không có các dòng lịch sử migration. Bản xuất ngày 21/10/2026 có 33 bảng nghiệp vụ + 1 bảng lịch sử, 6 view, 17 function và 35 trigger.

Chạy lại từ thư mục `backend`:

```powershell
python -m pip install -r tools/database-requirements.txt
python -X utf8 tools/export_schema.py
```

Script ưu tiên `DB_MIGRATION_URL` (direct connection), sau đó mới dùng `DB_URL`. Đọc username/password từ `.env` hoặc biến môi trường; không đưa mật khẩu vào tham số lệnh. Script chỉ đọc database và ghi đè file snapshot khi `pg_dump` thành công. Cần PostgreSQL client 18 trở lên trên PATH; workspace này có bản portable tại `../.tools/postgresql18/pg_dump.exe`. Máy khác tự cài client hoặc truyền `--pg-dump "C:\path\to\pg_dump.exe"`.

Có thể đổi nơi xuất bằng `python tools/export_schema.py --output schema-latest.sql`. Chia sẻ `schema.sql` cùng migrations và hướng dẫn nghiệp vụ cho agent đồng đội. Snapshot dùng để đọc/đối chiếu; khi dựng database backend mới, chạy Flyway qua ứng dụng như phần trên. Không import snapshot vào database đã có bảng; không dùng snapshot thay cho migration hoặc lịch sử Flyway.

## ORM: Spring Data JPA + Hibernate

`pom.xml` đã có `spring-boot-starter-data-jpa`, Hibernate và PostgreSQL JDBC driver. Module media đã có entity/projection repository trên bảng V2; các module khác bổ sung khi triển khai. Giữ bộ này cho ASL:

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

Profile `integration` chỉ ở test resources, dùng `TEST_DB_*` cho cả datasource và Flyway, không fallback sang `DB_*` của app. Thiếu cấu hình hoặc không kết nối được sẽ làm test thất bại, không tự skip. Test chạy migration thật, kiểm tra chạy lại Flyway, HTTP và health/readiness; test cuối FoundationIT đóng pool của chính tiến trình test để mô phỏng mất DB, không tắt Neon. Bản thân suite không drop/clean database; MediaIT giữ fixture trên database test và dùng ID ngẫu nhiên để chạy lại. Không dùng chung branch/database test cho nhiều lần chạy đồng thời. Ngày 21/10/2026, FoundationIT và MediaIT đã PASS trên database Neon tạm riêng; database tạm của phiên kiểm chứng đã được dọn. MediaIT có test tải byte từ Pocket Sign, cần mạng ra ngoài và phụ thuộc availability nguồn development này.

Kiểm chứng schema bằng fixture contract, trên database dev/test chưa có lesson hoặc account:

```powershell
python -m pip install -r tools/database-requirements.txt
python tools/verify_schema.py --env-file .env --report ../plan/verification/schema-installed-report.json
```

Script dùng direct URL và `DB_*` từ file/environment, thử ghi dữ liệu rồi **luôn rollback**, không lưu fixture. Tùy chọn `--trial` chỉ dùng trước migration trên database chưa có bảng `asl`: chạy V1/V2 trong transaction rollback, không tạo Flyway history. Không thay thế việc áp dụng migration bằng Spring Boot/Flyway. Script cần thư mục `api-contract` nằm cạnh backend để đọc examples. Kết quả chi tiết ở [verification](../plan/verification/README.md).

Tham khảo: [Neon Java/JDBC](https://neon.com/docs/guides/java), [direct và pooled connections](https://neon.com/docs/connect/connection-pooling), [Spring Data JPA](https://spring.io/projects/spring-data-jpa/), [Spring config import](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## Quy ước kiến trúc

Module nằm tại `com.asl.backend.<module>`: `domain`, `application`, `adapter.in.web`, `adapter.out.persistence`, `configuration`. Chỉ tạo lớp khi có use case; không tạo hàng loạt placeholder.

- `domain`: Java thuần, không phụ thuộc application/framework.
- `application`: use case, port, điều phối nghiệp vụ; API công khai ở `application.api`, outbound ports ở `application.port.out`.
- Module khác chỉ dùng `application.api` (interface và kiểu dữ liệu API); không dùng HTTP DTO hoặc JPA entity của nhau.
- `configuration`: Spring wiring; adapter transaction bao quanh use case, không thêm `@Transactional` vào application.
- `platform`: các adapter dùng chung cho HTTP; không trở thành nơi gom domain nghiệp vụ.
- Flyway giữ history trong `public`; dữ liệu nghiệp vụ ở `asl`. V2 là baseline đầy đủ; migration tiếp theo thêm khi cần thay đổi schema, không sửa migration đã chạy. Hibernate chỉ validate.

Request ID do server sinh UUID, trả ở `X-Request-Id` và error body, dùng trong MDC. Validation payload trả 422; JSON/query sai trả 400. Body có field lạ bị từ chối. Các lỗi HTTP giao thức giữ status tương ứng; lỗi không dự kiến trả 500, không lộ stack trace qua response.

Health/readiness là endpoint vận hành ngoài contract mobile. Liveness không phụ thuộc DB; readiness có DB. Chỉ public health, không expose env/config/metrics. Media controller public, chưa triển khai auth.

## Module 01-media

`POST /api/v1/media/urls` nhận `{"assetIds":["UUID"]}` không cần đăng nhập. Danh sách có 1–100 UUID duy nhất; response trả `items` gồm assetId, url, expiresAt, mimeType, durationMs. Một ID không khả dụng làm toàn bộ batch thất bại: missing/draft/future 404, revoked 410. JSON/field lạ trả 400; validation trả 422; rate limit 429; lỗi provider/DB tạm thời 503. Response của operation dùng `Cache-Control: no-store`.

| Cấu hình | Mặc định | Ý nghĩa |
|---|---|---|
| `MEDIA_PUBLIC_BASE_URL` | Rỗng | HTTPS origin/prefix của public CDN; thiếu cấu hình trả 503 khi cần cấp URL |
| `MEDIA_RATE_LIMIT_REQUESTS` | `120` | Số request trên mỗi địa chỉ socket trong một cửa sổ, mỗi instance |
| `MEDIA_RATE_LIMIT_WINDOW` | `PT1M` | Duration của cửa sổ rate limit |
| `MEDIA_RATE_LIMIT_MAX_CLIENTS` | `10000` | Số IP tối đa đang được theo dõi; khi đầy, IP mới nhận 429 |

Storage key trong `asl.media_assets` là đường dẫn tương đối với base URL, không phải URL do client gửi. Adapter encode từng segment, chặn traversal/URL tuyệt đối và cấu hình `.invalid`. Public URL có `expiresAt=null`; có thể thay provider qua `MediaUrlProvider` để ký URL với expiry thật. Không HEAD/download mỗi request. Revoke DB chặn lần refresh tiếp theo; muốn vô hiệu URL public đã cấp phải thu hồi bytes tại CDN.

Các module xuất bản gọi `MediaApi.publish(assetId)` sau khi metadata/bytes/rights đã được chuẩn bị; gọi `revoke(assetId)` để thu hồi vĩnh viễn. Cả hai idempotent và tham gia transaction của caller. Media không truy vấn catalog/learning/collection membership. Chưa có CMS/importer/upload API, và ứng dụng không tự seed dữ liệu dev vào DB.

Fixture `hello.mp4` trong MediaIT dẫn nguồn manifest/Pocket Sign, checksum lấy từ byte mẫu đã kiểm tra. Những row khác chỉ là metadata tổng hợp cho test. Không coi link development là giấy phép production. Khi chạy thật, cấu hình CDN và chuẩn bị asset có quyền sử dụng; không điền một origin giả để vượt lỗi 503.

Chạy kiểm chứng từ workspace root sau `mvnw.cmd verify` trong `backend` với database test riêng:

```powershell
python -X utf8 backend/tools/verify_media_responses.py --report plan/verification/media-contract-report.json
python -X utf8 backend/tools/validate_contract.py --report plan/verification/contract-report.json
```

`mvnw.cmd test` chạy unit/HTTP/ArchUnit không cần DB. `verify` thêm migration/JPA/transaction/concurrency, HTTP thật và tải media. Công cụ conformance đọc response thực tế trong `backend/target/media-http`; thiếu capture sẽ fail. Xem [ADR-0011](../plan/adr/0011.md) và [báo cáo nghiệm thu](../plan/verification/01-media.md).
