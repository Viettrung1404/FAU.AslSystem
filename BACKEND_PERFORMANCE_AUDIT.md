# BÁO CÁO KIỂM TRA HIỆU SUẤT BACKEND TOÀN DIỆN
## COMPREHENSIVE BACKEND PERFORMANCE AUDIT REPORT

**Dự án:** ASL Mobile Backend  
**Ngày thực hiện:** 18/11/2026  
**Chuyên gia thực hiện:** Senior Backend Engineer – Performance Optimization Specialist  
**Phiên bản Runtime/Framework:** Java 21, Spring Boot 4.1.1, PostgreSQL (Neon Serverless v18.6)  
**Trạng thái kiểm tra:** Hoàn thành audit tĩnh và phân tích mã nguồn (Không can thiệp mã nguồn)  

---

## 1. PROJECT OVERVIEW

### 1.1. Kiến trúc Backend hiện tại
- **Kiến trúc tổng thể:** Clean Architecture / Hexagonal Architecture (Ports and Adapters) với phân chia ranh giới module nghiêm ngặt, được bảo đảm bằng bộ kiểm tra cấu trúc ArchUnit (`ArchitectureTest.java`).
- **Phân lớp bên trong từng module:**
  - `domain`: Java thuần (POJO/Records), không phụ thuộc framework, chứa domain rules và immutable models.
  - `application`: Use cases, orchestration, định nghĩa Public Inbound API (`application.api.*`) và Outbound Ports (`application.port.out.*`).
  - `adapter.in.web`: Spring Web MVC REST Controllers, HttpPolicies (Rate limiters, interceptors), validation, DTOs.
  - `adapter.in.transaction`: Transaction boundary wrappers (`TransactionTemplate`), đảm bảo use case không bị gán `@Transactional` trực tiếp vào application layer.
  - `adapter.out.persistence`: Triển khai persistence ports bằng `JdbcTemplate` / `NamedParameterJdbcTemplate` hoặc Spring Data JPA `EntityManager`.
  - `configuration`: Spring `@Configuration` wiring các bean và infrastructure.
  - `platform`: Cấu hình dùng chung: Error Controller, Global Exception Handler, `RequestIdFilter` (MDC tracing), `RequestBudget`.

### 1.2. Công nghệ đang sử dụng
- **Ngôn ngữ:** Java 21 (sử dụng Records, Pattern Matching, Switch Expressions, Text Blocks).
- **Backend Framework:** Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`).
- **Database:** PostgreSQL trên nền tảng Neon Cloud Serverless (kết nối trực tiếp qua TLS/SSL `sslmode=require&channelBinding=require`).
- **Data Access:** 
  - Spring Data JPA / Hibernate (Jakarta Persistence) với cấu hình `open-in-view: false`, `ddl-auto: validate`.
  - `JdbcTemplate` & `NamedParameterJdbcTemplate` cho phần lớn các truy vấn tra cứu, cursor-based seek và batch mutations.
- **Database Migration:** Flyway (`flyway-database-postgresql`), gồm 6 bản migration: `V1` tạo schema `asl`; `V2` baseline 33 bảng, 6 views, 17 functions, 35 triggers; `V3` - `V6` cập nhật schema, Firebase UID, checkpoint validation.
- **Authentication:** Firebase Admin SDK 9.11.0 (Google / Apple OAuth ID Token verification).
- **Media Delivery:** Public CDN (ImageKit CDN / S3-compatible) kết hợp `PublicCdnUrlProvider`.
- **JSON Processing:** Jackson 3.x (`tools.jackson.databind.json.JsonMapper`).

### 1.3. Cấu trúc Database & Bảng dữ liệu
Hệ thống sử dụng schema chuyên biệt `asl`, lưu trữ 33 bảng nghiệp vụ chính:
- **Identity & Legal:** `app_users`, `user_consents`, `legal_documents`, `app_settings`.
- **Catalog & Curricula:** `units`, `lessons`, `lesson_prerequisites`, `lesson_versions`, `lesson_version_signs`, `lesson_exercises`, `exercise_signs`, `lesson_exercise_media`, `conversation_turns`.
- **Dictionary:** `signs`, `collections`, `collection_signs`.
- **User Progress & Learning:** `lesson_attempts`, `completion_ledger`, `favorites`, `personal_collections`, `user_preferences`.
- **Sync Engine:** `sync_streams`, `sync_changes`, `sync_mutation_receipts`.
- **Reporting & Feedback:** `user_reports`.
- **Monetization & Store:** `store_products`, `subscriptions`, `store_purchases`, `store_webhook_events`.
- **Views (Tính toán phân tích):** `user_total_points`, `user_completed_lessons`, `user_learned_signs`, `user_activity_dates`, `user_streaks`, `user_entitlements`.

### 1.4. Luồng xử lý Request chuẩn (Request Lifecycle)
1. **Network Ingress:** HTTP Request đến máy chủ (Tomcat Embedded).
2. **Filter Chain:** `RequestIdFilter` gắn UUID vào Header `X-Request-Id` và thiết lập `MDC.put("requestId", id)`.
3. **Web Interceptor (`*HttpPolicy`):**
   - Đặt header phản hồi `Cache-Control: no-store`.
   - Kiểm tra rate limit in-memory thông qua IP client (`request.getRemoteAddr()`).
   - Kiểm tra whitelist query parameters (từ chối tham số lạ bằng HTTP 400).
4. **Controller:** Validate tham số, chuyển đổi sang UUID/Domain types, chuyển tiếp tới `*Api`.
5. **Transactional Adapter (`Transactional*Api`):**
   - Khởi tạo transaction thông qua `TransactionTemplate` (phần lớn là `READ_ONLY` với mức cô lập `ISOLATION_REPEATABLE_READ`).
   - Giữ kết nối cơ sở dữ liệu từ HikariCP Pool.
6. **Service Layer:** Thực thi logic nghiệp vụ, giải mã Cursor (HMAC), gọi Outbound Ports.
7. **Persistence Adapter / External Service:**
   - Thực thi câu lệnh SQL qua `JdbcTemplate` / JPA `EntityManager`.
   - Hoặc gọi outbound API (Firebase Auth SDK).
8. **Response Mapping:** Đóng gói DTO Records, trả về JSON cho client, giải phóng transaction và connection.

---

## 2. NHỮNG VẤN ĐỀ HIỆU SUẤT ĐÃ PHÁT HIỆN (PERFORMANCE ISSUES)

---

### ISSUE #1: N+1 Query Problem trong Dictionary Service (`render` loop)

- **Vị trí code:** 
  - [DictionaryService.java#L46-L47](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/dictionary/application/DictionaryService.java#L46-L47)
  - [DictionaryService.java#L65](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/dictionary/application/DictionaryService.java#L65)
  - [JpaAssetRepository.java#L17-L25](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/media/adapter/out/persistence/JpaAssetRepository.java#L17-L25)
- **Cách code hiện tại hoạt động:**
  Khi người dùng gọi API phân trang danh sách ký hiệu hoặc bộ sưu tập (`/api/v1/signs`, `/api/v1/collections`, `/api/v1/search`, `/api/v1/collections/{id}/signs`):
  ```java
  // DictionaryService.java
  var items = visible.stream().map(row -> scope.equals("search")
          ? Map.<String,Object>of("type", row.type(), row.type().equals("SIGN") ? "sign" : "collection", render(row)) : render(row)).toList();
  ...
  private Map<String,Object> render(Entry e, boolean signDetail) {
      Object asset;
      try { asset = e.assetId() == null ? null : media.refresh(List.of(e.assetId())).getFirst(); }
  ```
  Với mỗi dòng dữ liệu `Entry` (mặc định limit 20, tối đa 100), hàm `render(e)` được gọi trong vòng lặp Stream. Mỗi lần gọi lại phát sinh một lời gọi riêng biệt `media.refresh(List.of(e.assetId()))`. Hàm này chuyển tiếp đến `JpaAssetRepository.findAll(ids)` để truy vấn bảng `asl.media_assets`.
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Đây là lỗi **N+1 Query kinh điển** xuyên qua ranh giới dịch vụ. Với trang 100 ký hiệu: 1 truy vấn lấy danh sách signs + 100 truy vấn độc lập đến bảng `media_assets` = **101 truy vấn SQL liên tiếp**.
  - Database PostgreSQL đặt trên Neon Cloud có độ trễ mạng (RTT) khoảng 15–30ms. 100 truy vấn tuần tự sẽ tiêu tốn:
    $$100 \times 20\text{ms} = 2.000\text{ms} = 2\text{ giây}$$
    chỉ riêng cho việc chờ đợi round-trip mạng!
  - Trong suốt 2 giây này, kết nối HikariCP bị giữ chặt trong transaction `REPEATABLE_READ`.
- **Mức độ nghiêm trọng:** `CRITICAL`
- **Giải pháp đề xuất:**
  Gom toàn bộ danh sách `assetId` của trang (`visible`) thành một tập hợp duy nhất `Set<UUID> assetIds`, gọi `media.refresh(batchIds)` đúng **1 lần duy nhất**, lưu kết quả vào `Map<UUID, MediaItem>`, sau đó truyền map này vào hàm `render(entry, mediaMap)`.
- **Lợi ích dự kiến:**
  - Giảm số lượng truy vấn từ $N+1$ xuống còn **2 truy vấn**.
  - Thời gian phản hồi API giảm từ ~2.200ms xuống còn **< 60ms** (giảm 97% latency).
  - Giải phóng kết nối database nhanh gấp 30 lần.
- **Rủi ro / Trade-off:** Không có rủi ro logic, cần giữ nguyên thứ tự kết quả trả về của client.

---

### ISSUE #2: Query Avalanche (N×6 SQL Queries) trong Practice Generation & Availability

- **V vị trí code:**
  - [PracticeService.java#L26-L33](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/practice/application/PracticeService.java#L26-L33)
  - [LearningPracticeReader.java#L11-L26](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/learning/application/LearningPracticeReader.java#L11-L26)
  - [JdbcLessonRepository.java#L25-L51](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/learning/adapter/out/persistence/JdbcLessonRepository.java#L25-L51)
- **Cách code hiện tại hoạt động:**
  API `POST /api/v1/practice/availability` và `POST /api/v1/practice/sets` nhận danh sách `completedLessons: [{lessonId, versionId}]` từ client (validation cho phép lên đến 10.000 sources - dòng 27).
  Để xây dựng pool câu hỏi, `PracticeService` chạy vòng lặp:
  ```java
  for (var source:ordered) for (var sign:content.signs(source)) ...
  ```
  Hàm `LearningPracticeReader.signs(source)` gọi `repository.find(lessonId, versionId)`.
  Bên trong `JdbcLessonRepository.load()`, để nạp đầy đủ 1 lesson version, hệ thống thực thi **6 câu lệnh SQL tuần tự**:
  1. `SELECT * FROM asl.lesson_versions ...`
  2. `SELECT * FROM asl.lesson_version_signs ...`
  3. `SELECT exercise_id, sign_id FROM asl.exercise_signs ...`
  4. `SELECT exercise_id, slot_key, media_asset_id FROM asl.lesson_exercise_media ...`
  5. `SELECT * FROM asl.lesson_exercises ...` (kèm Jackson JSON parse `payload` & `answer_key`)
  6. `SELECT * FROM asl.conversation_turns ...`
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Nếu người dùng đã học 30 bài học: backend thực hiện $30 \times 6 =$ **180 truy vấn SQL tuần tự** qua Internet đến Neon DB.
  - Nếu người dùng đã học 50 bài học: $50 \times 6 =$ **300 truy vấn SQL**.
  - Toàn bộ nội dung bài học, bài tập JSON, câu đàm thoại bị tải lên RAM rồi bị vứt bỏ, chỉ để lấy vài UUID của ký hiệu (`Sign`) và ảnh minh họa `VIDEO_TO_IMAGE`.
  - Nếu một client gửi 1.000 lessons: backend sẽ thực thi **6.000 truy vấn SQL**, gây tắc nghẽn toàn bộ kết nối DB và làm sập connection pool.
- **Mức độ nghiêm trọng:** `CRITICAL`
- **Giải pháp đề xuất:**
  1. Tạo một phương thức chuyên biệt trong `LearningRepository` / `LessonRepository` (hoặc truy vấn JDBC chuyên biệt) nhận danh sách `List<Source>` để batch load ký hiệu và ảnh đại diện chỉ bằng **1 hoặc 2 câu lệnh SQL JOIN/IN**:
     ```sql
     SELECT s.lesson_version_id, s.sign_id, s.name_snapshot, s.video_asset_id, s.kind_snapshot,
            lem.media_asset_id AS image_asset_id
     FROM asl.lesson_version_signs s
     LEFT JOIN asl.lesson_exercises le ON le.lesson_version_id = s.lesson_version_id AND le.exercise_type = 'VIDEO_TO_IMAGE'
     ...
     WHERE s.lesson_version_id IN (:versionIds)
     ```
  2. Caching: Vì nội dung của một `versionId` đã xuất bản là **bất biến (immutable)**, snapshot danh sách `Sign` của một version có thể được lưu trữ trong In-Memory Cache (Caffeine). Lần luyện tập tiếp theo chỉ tốn 0ms và 0 truy vấn DB.
  3. Giới hạn tham số đầu vào: Siết chặt `sources.size()` từ 10.000 xuống mức thực tế (ví dụ: tối đa 100 lessons gần nhất).
- **Lợi ích dự kiến:**
  - Giảm từ 180–300 truy vấn xuống còn **1 truy vấn duy nhất** (hoặc 0 truy vấn khi có cache).
  - Giảm thời gian phản hồi API tạo bài luyện tập từ 3–8 giây xuống còn **< 50ms**.
  - Giảm 90% áp lực CPU và cấp phát bộ nhớ rác (Garbage Collection).
- **Rủi ro / Trade-off:** Cần kiểm tra cẩn thận logic khớp ảnh của dạng bài tập `VIDEO_TO_IMAGE` để đảm bảo kết quả trùng khớp chính xác với rule hiện tại.

---

### ISSUE #3: Nghẽn cổ chai luồng (Thread Serialization) do từ khóa `synchronized` trên Web Interceptors Singleton

- **Vị trí code:**
  - [CatalogHttpPolicy.java#L19](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/catalog/adapter/in/web/CatalogHttpPolicy.java#L19)
  - [LearningHttpPolicy.java#L19](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/learning/adapter/in/web/LearningHttpPolicy.java#L19)
  - [MediaHttpPolicy.java#L34](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/media/adapter/in/web/MediaHttpPolicy.java#L34)
  - [RequestBudget.java#L16](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/platform/application/api/RequestBudget.java#L16)
- **Cách code hiện tại hoạt động:**
  ```java
  // CatalogHttpPolicy.java
  @Override public synchronized boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) { ... }
  ```
  Trong Spring Web MVC, các lớp `HandlerInterceptor` được Spring khởi tạo dưới dạng **Singleton bean**.
  Phương thức `preHandle` được gắn từ khóa `synchronized`.
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Mọi request HTTP gửi tới các endpoint của Catalog (`/api/v1/config`, `/api/v1/home`, `/api/v1/units`, `/api/v1/units/{id}`), Learning (`/api/v1/lessons/{id}`), Media (`/api/v1/media/urls`) đều phải tranh chấp **1 monitor lock duy nhất** của interceptor đó.
  - Khi có 200 người dùng đồng thời gửi request, 199 worker thread của Tomcat bị **BLOCK** hoàn toàn ở trạng thái chờ đợi lock (Monitor Contention). Mọi lợi thế đa nhân (Multi-core CPU) bị vô hiệu hóa hoàn toàn ở tầng HTTP ingress.
  - Bên trong `CatalogHttpPolicy` và `LearningHttpPolicy`, mã nguồn còn thực hiện thao tác xóa/chèn trên `TreeSet` (cây Đỏ-Đen $O(\log N)$ với $N=10.000$) ngay trong khi đang giữ lock này!
  - Trong `RequestBudget`, `public synchronized long take(String client)` cũng sử dụng một lock độc quyền duy nhất cho mọi request xác thực của Dictionary, Practice, Identity, Sync.
  - Đặc biệt: Nếu dự án bật **Java 21 Virtual Threads (Project Loom)**, việc chặn trên `synchronized` sẽ gây ra hiện tượng **Thread Pinning** (ghim chặt Carrier Thread của hệ điều hành), làm vô hiệu hóa khả năng mở rộng của Virtual Threads.
- **Mức độ nghiêm trọng:** `HIGH`
- **Giải pháp đề xuất:**
  - Thay thế cấu trúc dữ liệu `HashMap + TreeSet` bằng bộ đếm phân tán hoặc bộ nhớ thread-safe không khóa: `ConcurrentHashMap<String, AtomicInteger>` kết hợp sliding window, hoặc sử dụng thư viện rate-limiting chuẩn hóa như Bucket4j (sử dụng atomics không khóa).
  - Loại bỏ hoàn toàn từ khóa `synchronized` trên `preHandle`.
- **Lợi ích dự kiến:**
  - Khả năng xử lý đồng thời (Throughput) tăng từ vài trăm req/s lên hàng chục nghìn req/s mà không bị nghẽn luồng.
  - Tận dụng tối đa 100% các lõi CPU của máy chủ.
- **Rủi ro / Trade-off:** Cần xử lý dọn dẹp các bucket hết hạn theo cơ chế định kỳ (cleaner task) hoặc lazy eviction an toàn luồng.

---

### ISSUE #4: Vô hiệu hóa GIN Trigram Index do sử dụng hàm `strpos()` trong Dictionary Search

- **Vị trí code:**
  - [JdbcDictionaryRepository.java#L34](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/dictionary/adapter/out/persistence/JdbcDictionaryRepository.java#L34)
  - [V2__create_mobile_schema.sql#L720-L723](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/resources/db/migration/V2__create_mobile_schema.sql#L720-L723)
- **Cách code hiện tại hoạt động:**
  Trong migration `V2`, cơ sở dữ liệu đã chuẩn bị sẵn GIN Trigram Indexes:
  ```sql
  CREATE INDEX idx_sign_search ON signs USING gin (search_name gin_trgm_ops);
  CREATE INDEX idx_collection_search ON collections USING gin (search_name gin_trgm_ops);
  ```
  Tuy nhiên, trong `JdbcDictionaryRepository.java`, câu truy vấn tìm kiếm lại được viết:
  ```java
  if (scope.equals("search")) { 
      sql += " and strpos(search_name,lower(normalize(btrim(?), NFC)))>0"; 
      args.add(q); 
  }
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Trong PostgreSQL, extension `pg_trgm` với class `gin_trgm_ops` chỉ hỗ trợ tăng tốc cho các toán tử: `LIKE`, `ILIKE`, `~`, `~*`, `%` (similarity).
  - Hàm `strpos(string, substring) > 0` là một function call thông thường, **Query Planner của PostgreSQL KHÔNG THỂ sử dụng GIN index** cho điều kiện này!
  - Hậu quả: Khi người dùng tìm kiếm từ điển (`/api/v1/search?q=...`), PostgreSQL buộc phải thực hiện **Sequential Scan (quét toàn bộ bảng)** trên `signs` và `collections`. Khi số lượng ký hiệu tăng lên hàng nghìn hoặc chục nghìn từ, CPU của database sẽ bị đẩy lên 100% cho mỗi lượt gõ phím tìm kiếm.
- **Mức độ nghiêm trọng:** `HIGH`
- **Giải pháp đề xuất:**
  - Chuyển đổi điều kiện tìm kiếm sang cú pháp tương thích với `pg_trgm`:
    ```sql
    search_name LIKE '%' || ? || '%'
    -- hoặc sử dụng toán tử regex:
    search_name ~ ?
    ```
  - Giá trị tham số truyền vào được normalize trước tại Java service: `%` + `DictionaryService.normalize(q)` + `%`.
- **Lợi ích dự kiến:**
  - PostgreSQL kích hoạt `Bitmap Index Scan` trên `idx_sign_search` và `idx_collection_search`.
  - Tốc độ tìm kiếm tăng từ hàng chục/trăm milliseconds xuống còn **< 2ms** (nhanh hơn gấp 50-100 lần khi dữ liệu lớn).
- **Rủi ro / Trade-off:** Cần escape các ký tự đặc biệt (`%`, `_`) trong chuỗi tìm kiếm nếu dùng toán tử `LIKE` để tránh người dùng thực hiện wildcard scan ngoài ý muốn.

---

### ISSUE #5: Outbound Network Request tới Firebase Auth trên MỌI Request có xác thực

- **Vị trí code:**
  - [FirebaseIdTokenVerifier.java#L48](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/identity/adapter/out/firebase/FirebaseIdTokenVerifier.java#L48)
- **Cách code hiện tại hoạt động:**
  ```java
  // FirebaseIdTokenVerifier.java
  var verified = auth().verifyIdToken(token, true);
  ```
  Tham số thứ hai `checkRevoked` được đặt cứng thành `true`.
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Khi `checkRevoked = true`, Firebase Admin SDK **bắt buộc phải thực hiện một cuộc gọi mạng HTTPS ra bên ngoài** tới server của Google (`https://identitytoolkit.googleapis.com/...`) để kiểm tra xem refresh token của user có bị thu hồi hay không.
  - Mỗi một request có Bearer token (như gọi `POST /api/v1/sync/mutations`, `GET /api/v1/sync/changes`, `GET /api/v1/me`, `GET /api/v1/me/stats`, xem collection riêng, gửi report) đều phải gánh thêm:
    $$\text{Độ trễ mạng tới Google: } 100\text{ms} - 300\text{ms}$$
  - Nếu kết nối mạng quốc tế chập chờn hoặc Google API bị nghẽn, toàn bộ backend ASL sẽ bị chậm theo hoặc ném lỗi `503 SERVICE_UNAVAILABLE`.
  - Nếu đặt `checkRevoked = false`, Firebase Admin SDK sẽ xác thực chữ ký mật mã (cryptographic signature) của JWT Token **ngay tại bộ nhớ cục bộ** bằng public keys của Google (vốn được SDK tự động tải và cache vài tiếng một lần). Thời gian xác thực cục bộ chỉ mất **< 0.5ms**.
- **Mức độ nghiêm trọng:** `HIGH`
- **Giải pháp đề xuất:**
  - Cân nhắc cấu hình `checkRevoked = false` cho các API đọc dữ liệu thông thường hoặc sync thường xuyên (vì JWT của Firebase vốn dĩ chỉ có hạn ngắn 1 giờ).
  - Hoặc nếu yêu cầu nghiệp vụ bắt buộc phải kiểm tra thu hồi token (revocation check), cần áp dụng cơ chế **In-memory Cache ngắn hạn** (ví dụ Caffeine Cache với TTL = 2–5 phút cho các token đã xác minh thành công).
- **Lợi ích dự kiến:**
  - Loại bỏ hoàn toàn 100–300ms độ trễ mạng ngoại vi trên mỗi request có đăng nhập.
  - Tăng độ bền vững của hệ thống khi mạng quốc tế có sự cố.
- **Rủi ro / Trade-off:** Nếu user đổi mật khẩu hoặc đăng xuất, quyền truy cập sẽ duy trì tối đa thêm 2-5 phút nếu áp dụng cache, đây là trade-off hoàn toàn chấp nhận được trong kiến trúc microservices/mobile.

---

### ISSUE #6: Cấu hình HikariCP Connection Pool quá nhỏ và thiếu tham số bảo vệ

- **Vị trí code:**
  - [application.yml#L12-L14](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/resources/application.yml#L12-L14)
  - [application.yml#L8-L11](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/resources/application.yml#L8-L11)
- **Cách code hiện tại hoạt động:**
  ```yaml
  hikari:
    maximum-pool-size: ${DB_POOL_SIZE:5}
    minimum-idle: 0
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  1. `maximum-pool-size: 5`: Quá thấp đối với một API backend phục vụ ứng dụng di động. Tomcat mặc định có thể tiếp nhận hàng trăm request đồng thời. Với pool size = 5, chỉ cần 5 request đồng thời rơi vào các endpoint nặng (như Dictionary N+1 queries hoặc Practice 180 queries), toàn bộ pool sẽ cạn kiệt. Request thứ 6 trở đi sẽ bị block chờ kết nối, dẫn đến lỗi `Connection is not available, request timed out after 30000ms` (HTTP 500/503).
  2. `minimum-idle: 0`: Khi không có request trong một khoảng thời gian, Hikari đóng toàn bộ kết nối. Khi người dùng mở app lại, backend phải tạo mới kết nối từ đầu tới Neon DB qua Internet (TCP Handshake + TLS Negotiate + PostgreSQL Auth + Startup). Quá trình này tốn từ 200–400ms cho cold-start.
  3. Thiếu cấu hình `leak-detection-threshold`: Nếu có một transaction bị treo hoặc giữ kết nối quá lâu, hệ thống không có cảnh báo để phát hiện rò rỉ kết nối.
  4. Thiếu `max-lifetime`: Neon Serverless có cơ chế suspend compute node khi không hoạt động. Nếu `max-lifetime` không được cấu hình thấp hơn timeout của Neon (thường nên để 10–15 phút), app có thể nhận kết nối đã bị đóng bởi firewall/proxy của Neon.
- **Mức độ nghiêm trọng:** `HIGH`
- **Giải pháp đề xuất:**
  - Nâng `maximum-pool-size` lên mức phù hợp với tài nguyên instance và gói Neon (ví dụ: 15–20 cho dev/staging, 30–50 cho production).
  - Đặt `minimum-idle` bằng ít nhất 5 (hoặc bằng `maximum-pool-size` trên môi trường cố định) để loại bỏ cold-start latency.
  - Bổ sung cấu hình tối ưu:
    ```yaml
    hikari:
      connection-timeout: 10000 # 10s timeout thay vì 30s
      max-lifetime: 600000      # 10 phút để tương thích Neon
      idle-timeout: 300000     # 5 phút
      leak-detection-threshold: 5000 # Cảnh báo nếu giữ connection > 5s
    ```
- **Lợi ích dự kiến:**
  - Ngăn ngừa tình trạng sập pool (Connection Exhaustion) khi có lượng truy cập tăng đột biến.
  - Loại bỏ hoàn toàn độ trễ khởi tạo kết nối (200-400ms) sau khoảng thời gian nhàn rỗi.
- **Rủi ro / Trade-off:** Cần kiểm tra giới hạn connection tối đa cho phép trên gói Neon (Compute Size) tương ứng.

---

### ISSUE #7: Hardcoded `Cache-Control: no-store` trên MỌI Endpoint (Kể cả dữ liệu tĩnh/bất biến)

- **Vị trí code:**
  - [CatalogHttpPolicy.java#L20](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/catalog/adapter/in/web/CatalogHttpPolicy.java#L20)
  - [LearningHttpPolicy.java#L20](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/learning/adapter/in/web/LearningHttpPolicy.java#L20)
  - [MediaHttpPolicy.java#L35](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/media/adapter/in/web/MediaHttpPolicy.java#L35)
  - [DictionaryHttpPolicy.java#L13](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/dictionary/adapter/in/web/DictionaryHttpPolicy.java#L13)
  - [PracticeHttpPolicy.java#L11](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/practice/adapter/in/web/PracticeHttpPolicy.java#L11)
  - [IdentityHttpPolicy.java#L16](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/identity/adapter/in/web/IdentityHttpPolicy.java#L16)
- **Cách code hiện tại hoạt động:**
  Tại tầng interceptor của từng module, header luôn bị gán:
  ```java
  response.setHeader("Cache-Control", "no-store");
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Ứng dụng cấm client và các HTTP proxy trung gian lưu cache cho **100% các endpoint**.
  - Trong khi đó:
    - `/api/v1/lessons/{lessonId}?versionId={versionId}`: Nội dung bài học của một version đã xuất bản là **HOÀN TOÀN BẤT BIẾN (IMMUTABLE)**!
    - `/api/v1/config`: Cấu hình chính sách hệ thống chỉ thay đổi khi có bản phát hành mới.
    - `/api/v1/legal-documents`: Điều khoản sử dụng và chính sách bảo mật thay đổi theo chu kỳ nhiều tháng/năm.
    - `/api/v1/media/urls`: Public CDN URLs không có expiry (`expiresAt = null`).
  - Hậu quả: Mỗi khi người dùng bấm vào lại bài học, mở app, hay tra cứu từ điển, mobile client buộc phải gửi request về server, server lại truy vấn database, tiêu tốn băng thông và CPU một cách lãng phí.
- **Mức độ nghiêm trọng:** `MEDIUM`
- **Giải pháp đề xuất:**
  - Với các endpoint dữ liệu bất biến hoặc có phiên bản (`/api/v1/lessons/{id}` khi có `versionId`): Cho phép `Cache-Control: public, max-age=86400, immutable` hoặc sử dụng cơ chế `ETag` (bằng versionId hash).
  - Với `/api/v1/config`, `/api/v1/legal-documents`: Cung cấp `Cache-Control: public, max-age=3600` hoặc ETag.
  - Với dữ liệu cá nhân (`/api/v1/sync/*`, `/api/v1/me/*`): Giữ nguyên `private, no-store`.
- **Lợi ích dự kiến:**
  - Giảm đến 60–80% lưu lượng request tới backend cho các màn hình xem bài học, splash screen, điều khoản.
  - Người dùng mở bài học cũ tức thì (0ms latency từ HTTP cache của điện thoại).
- **Rủi ro / Trade-off:** Cần quản lý cache invalidation đúng đắn khi xuất bản version mới của lesson (nhưng URL API đã phân biệt rõ bằng query parameter `versionId` nên rất an toàn).

---

### ISSUE #8: Chưa bật HTTP Compression (Gzip / Brotli) cho Response Payloads

- **Vị trí code:**
  - [application.yml#L30-L34](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/resources/application.yml#L30-L34)
- **Cách code hiện tại hoạt động:**
  Phần cấu hình `server:` trong `application.yml` chưa khai báo nén:
  ```yaml
  server:
    port: ${PORT:8080}
    error:
      include-message: never
      include-stacktrace: never
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - Response JSON của API `/api/v1/lessons/{lessonId}` chứa toàn bộ danh sách ký hiệu, 13 loại bài tập, các lựa chọn, token bank, và các lượt đối thoại. Kích thước payload có thể từ **50KB đến 250KB** văn bản JSON thuần.
  - Tương tự, `/api/v1/home`, `/api/v1/search`, `/api/v1/sync/snapshot` trả về danh sách lớn các object.
  - Không có nén khiến dung lượng truyền tải qua mạng di động (3G/4G) tăng gấp 5–10 lần, làm chậm thời gian tải màn hình trên điện thoại và tốn chi phí băng thông egress.
- **Mức độ nghiêm trọng:** `MEDIUM`
- **Giải pháp đề xuất:**
  Kích hoạt nén GZIP trong Spring Boot:
  ```yaml
  server:
    compression:
      enabled: true
      mime-types: application/json,text/html,text/xml,text/plain
      min-response-size: 1024 # Chỉ nén response >= 1KB
  ```
- **Lợi ích dự kiến:**
  - Giảm kích thước payload JSON từ **70% đến 85%** (ví dụ: payload 150KB giảm xuống còn ~25KB).
  - Rút ngắn đáng kể thời gian download trên mạng di động.
- **Rủi ro / Trade-off:** Tăng rất ít tải CPU của server khi nén (với kích thước vài trăm KB, CPU overhead của Gzip là không đáng kể so với lợi ích mạng).

---

### ISSUE #9: 5 Truy vấn Tuần tự trên 5 SQL Views phức tạp khi gọi `GET /api/v1/me/stats`

- **Vị trí code:**
  - [JdbcSyncRepository.java#L85-L93](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/sync/adapter/out/persistence/JdbcSyncRepository.java#L85-L93)
  - [V2__create_mobile_schema.sql#L751-L776](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/resources/db/migration/V2__create_mobile_schema.sql#L751-L776)
- **Cách code hiện tại hoạt động:**
  Khi mobile gọi `GET /api/v1/me/stats`, phương thức `stats(owner)` thực thi 5 câu truy vấn độc lập:
  ```java
  result.put("totalPoints", jdbc.queryForObject("select total_points from asl.user_total_points where user_id=?", ...));
  result.put("completedLessonIds", jdbc.queryForList("select lesson_id from asl.user_completed_lessons where user_id=? order by lesson_id", ...));
  result.put("learnedSignIds", jdbc.queryForList("select sign_id from asl.user_learned_signs where user_id=? order by sign_id", ...));
  var streak = jdbc.queryForMap("select current_streak,record_streak from asl.user_streaks where user_id=?", owner);
  result.put("activityDates", jdbc.query("select activity_date from asl.user_activity_dates where user_id=? order by activity_date", ...));
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  - 5 truy vấn round-trip tuần tự tới Neon DB.
  - View `user_streaks` là một câu truy vấn CTE rất nặng:
    - Sử dụng Window Function `row_number() OVER (PARTITION BY user_id ORDER BY activity_date)` để tính ngày liên tiếp ("islands & gaps").
    - Aggregate nhóm theo island để tìm min/max/days.
    - Join tiếp với `app_users` và `user_preferences` để xác định múi giờ `today`.
  - View `user_learned_signs` thực hiện `JOIN lesson_attempts a JOIN lesson_version_signs s` với điều kiện `a.status = 'COMPLETED'`. Hiện bảng `lesson_attempts` chưa có index tối ưu cho `(user_id, lesson_version_id) WHERE status = 'COMPLETED'`.
- **Mức độ nghiêm trọng:** `MEDIUM`
- **Giải pháp đề xuất:**
  1. Thêm partial index hỗ trợ:
     ```sql
     CREATE INDEX idx_attempt_user_version ON asl.lesson_attempts(user_id, lesson_version_id) WHERE status = 'COMPLETED';
     ```
  2. Gom các truy vấn scalar (`totalPoints`, `currentStreak`, `recordStreak`) vào chung một câu truy vấn duy nhất.
  3. Với người dùng có lịch sử học tập dài hạn (vài tháng/năm), cân nhắc lưu trữ điểm tích lũy (`total_points`) và chuỗi ngày (`current_streak`) thành các cột materialized trong bảng `app_users` (cập nhật khi mutation `LESSON_ATTEMPT` trạng thái `COMPLETED` thành công) thay vì tính toán lại toàn bộ lịch sử từ đầu trên mỗi lần xem profile/stats.
- **Lợi ích dự kiến:**
  - Giảm từ 5 truy vấn xuống còn 2 truy vấn (1 lấy metrics tổng hợp, 1 lấy danh sách ID).
  - Tốc độ phản hồi API `/me/stats` tăng gấp 3–4 lần.
- **Rủi ro / Trade-off:** Cần kiểm tra kỹ logic đồng bộ streak khi múi giờ thay đổi.

---

### ISSUE #10: Rủi ro DoS và Memory Overflow trong `RequestBudget` khi Clean Expired Buckets

- **Vị trí code:**
  - [RequestBudget.java#L11-L24](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/platform/application/api/RequestBudget.java#L11-L24)
- **Cách code hiện tại hoạt động:**
  `RequestBudget` dùng một `LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>()` (mặc định theo insertion-order).
  Khi có request:
  ```java
  while (!buckets.isEmpty() && !buckets.firstEntry().getValue().until().isAfter(now)) 
      buckets.pollFirstEntry();
  ...
  buckets.put(client, new Bucket(bucket.until(), bucket.used() + 1));
  ```
- **Nguyên nhân ảnh hưởng hiệu suất & Rủi ro vận hành:**
  - Khi một client đã tồn tại trong `buckets` thực hiện request mới, `buckets.put(...)` sẽ ghi đè giá trị tại chỗ nhưng **KHÔNG đưa key đó về cuối của LinkedHashMap** (vì không bật cờ `accessOrder = true`).
  - Khi client này hết window 1 phút và được cấp window mới (`now.plus(window)`), nó vẫn nằm ở **ĐẦU HÀNG ĐỢI** (head của map).
  - Tại request tiếp theo, vòng lặp `while` kiểm tra `buckets.firstEntry().getValue().until().isAfter(now)`. Vì entry ở đầu map có `until` nằm ở tương lai (của window mới), điều kiện `!isAfter(now)` trở thành `false` $\rightarrow$ **Vòng lặp WHILE DỪNG NGAY LẬP TỨC**!
  - Toàn bộ các entry của các client khác nằm phía sau dù đã hết hạn từ lâu cũng **KHÔNG BAO GIỜ ĐƯỢC DỌN DẸP**!
  - Khi map đạt ngưỡng `maxClients: 10000`, hệ thống coi như bộ nhớ đã đầy và từ chối cấp slot cho các IP mới bằng mã lỗi 429!
- **Mức độ nghiêm trọng:** `MEDIUM`
- **Giải pháp đề xuất:**
  Sử dụng thư viện Cache có TTL tự động (như Caffeine với `expireAfterWrite`) hoặc tái cấu trúc `RequestBudget` sử dụng một queue thời gian riêng biệt kết hợp ConcurrentHashMap.
- **Lợi ích dự kiến:**
  Tránh rò rỉ bộ nhớ (memory leak ngầm) và tránh hiện tượng từ chối nhầm client hợp lệ (false-positive 429).
- **Rủi ro / Trade-off:** Cần chạy unit test tương thích với `RequestBudgetTest`.

---

### ISSUE #11: Rủi ro Rate Limiter khi Triển khai Phía sau Reverse Proxy / Multi-Instance

- **Vị trí code:**
  - [CatalogHttpPolicy.java#L21](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/catalog/adapter/in/web/CatalogHttpPolicy.java#L21)
  - [DictionaryHttpPolicy.java#L14](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/dictionary/adapter/in/web/DictionaryHttpPolicy.java#L14)
  - [README.md#L167](file:///d:/VS_Code/problems.Java/ASL%20App/backend/README.md#L167)
- **Cách code hiện tại hoạt động:**
  Hệ thống lấy IP thông qua `request.getRemoteAddr()`:
  ```java
  String ip = Objects.toString(request.getRemoteAddr(), "unknown");
  ```
  Và tài liệu README ghi rõ: *"Limiter không tin X-Forwarded-For; deployment qua proxy/nhiều instance cần chính sách tương ứng ở gateway"*.
- **Nguyên nhân ảnh hưởng hiệu suất & Khả năng mở rộng:**
  - Khi ứng dụng được deploy trên các nền tảng đám mây hiện đại (Render, AWS ECS, Google Cloud Run, Kubernetes, Nginx Ingress), toàn bộ request từ người dùng đều đi qua Reverse Proxy / Load Balancer.
  - `request.getRemoteAddr()` sẽ luôn trả về địa chỉ IP nội bộ của Load Balancer (ví dụ: `10.0.0.1`).
  - Hậu quả: **TẤT CẢ NGƯỜI DÙNG TRÊN THẾ GIỚI CHUNG NHAU 1 BUCKET RATE LIMIT DUY NHẤT** (ví dụ: 120 req/phút cho Catalog). Chỉ cần 10 người dùng lướt app cùng lúc, hệ thống sẽ chạm mốc 120 req/phút và **TOÀN BỘ NGƯỜI DÙNG SẼ BỊ CHẶN BỞI HTTP 429 RATE_LIMITED**!
  - Ngược lại, nếu deploy nhiều instance (Horizontal Scaling) mà không có proxy chặn, người dùng có thể bypass rate limit bằng cách gọi luân phiên qua các instance khác nhau.
- **Mức độ nghiêm trọng:** `HIGH` (Đặc biệt nghiêm trọng khi chuyển từ localhost lên Cloud/Render)
- **Giải pháp đề xuất:**
  - Cấu hình Spring Boot hỗ trợ proxy tin cậy: `server.forward-headers-strategy: framework` kết hợp khai báo dải IP của proxy nội bộ.
  - Hoặc đối với production nhiều instance: Tách tầng rate-limiting lên API Gateway (Cloudflare WAF / Kong / AWS WAF), hoặc sử dụng Redis Token Bucket chung cho toàn bộ cụm instance.
- **Lợi ích dự kiến:** Đảm bảo hệ thống hoạt động chính xác khi triển khai trên Render / Cloud mà không chặn nhầm người dùng.
- **Rủi ro / Trade-off:** Cần cấu hình chính xác subnet proxy tin cậy để tránh tấn công IP Spoofing qua header `X-Forwarded-For`.

---

### ISSUE #12: Trực tiếp Parse JSON Bằng Nhiều Unmanaged `JsonMapper` Instance Độc Lập

- **Vị trí code:**
  - [JdbcCatalogRepository.java#L16](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/catalog/adapter/out/persistence/JdbcCatalogRepository.java#L16)
  - [JdbcLessonRepository.java#L15](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/learning/adapter/out/persistence/JdbcLessonRepository.java#L15)
  - [JdbcReportRepository.java#L12](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/reporting/adapter/out/persistence/JdbcReportRepository.java#L12)
  - [JdbcSyncRepository.java#L20](file:///d:/VS_Code/problems.Java/ASL%20App/backend/src/main/java/com/asl/backend/sync/adapter/out/persistence/JdbcSyncRepository.java#L20)
- **Cách code hiện tại hoạt động:**
  Tại mỗi repository, một `JsonMapper` mới được khởi tạo bằng `new`:
  ```java
  private final JsonMapper json = JsonMapper.builder().build();
  ```
- **Nguyên nhân ảnh hưởng hiệu suất:**
  Mỗi `JsonMapper` tự duy trì bộ cache Serializer/Deserializer nội bộ riêng biệt. Khởi tạo phân tán nhiều mapper làm tăng mức tiêu thụ bộ nhớ Metaspace/Heap và không tận dụng được cấu hình dùng chung của Spring Boot Jackson (như naming strategy, date format, custom modules).
- **Mức độ nghiêm trọng:** `LOW`
- **Giải pháp đề xuất:**
  Inject bean `ObjectMapper` / `JsonMapper` do Spring Boot quản lý thông qua Dependency Injection.
- **Lợi ích dự kiến:** Tiết kiệm bộ nhớ Heap, đồng bộ cấu hình serialization toàn ứng dụng.
- **Rủi ro / Trade-off:** Không có.

---

## 3. DATABASE OPTIMIZATION PLAN

### 3.1. Danh mục Index Cần Thêm (Proposed New Indexes)

Dựa trên việc đối chiếu toàn bộ các câu lệnh SQL trong Repository và cấu trúc bảng thực tế:

| Bảng dữ liệu | Tên Index đề xuất | Cột & Điều kiện (Definition) | Lý do kỹ thuật / Truy vấn hưởng lợi |
|---|---|---|---|
| `asl.units` | `idx_units_active_seek` | `(position, id) WHERE published_at IS NOT NULL AND retired_at IS NULL` | Tối ưu hóa truy vấn Keyset Pagination trong `JdbcCatalogRepository.findUnits`: loại bỏ toàn bộ Seq Scan / Filter. |
| `asl.lesson_attempts` | `idx_attempts_user_version_comp` | `(user_id, lesson_version_id) WHERE status = 'COMPLETED'` | Tối ưu hóa View `user_learned_signs`: loại bỏ Seq Scan khi join `lesson_attempts` với `lesson_version_signs`. |
| `asl.user_reports` | `idx_reports_user_id` | `(user_id)` | Foreign key index: Tăng tốc cascade check và tra cứu báo cáo theo người dùng. |
| `asl.user_reports` | `idx_reports_lesson_version` | `(lesson_version_id) WHERE lesson_version_id IS NOT NULL` | Foreign key index: Tránh lock bảng khi cập nhật lesson version. |
| `asl.collection_signs` | `idx_col_signs_sign_id` | `(sign_id)` | Foreign key index: Tránh table lock khi cập nhật/xóa ký hiệu trong `signs`. |
| `asl.lesson_version_signs`| `idx_lvs_video_asset` | `(video_asset_id)` | Foreign key index: Phục vụ truy vấn tra cứu tài nguyên video. |
| `asl.lesson_version_signs`| `idx_lvs_sign_id` | `(sign_id)` | Foreign key index: Tăng tốc tra cứu sign thuộc version nào. |
| `asl.lesson_exercise_media`| `idx_lem_media_asset` | `(media_asset_id)` | Foreign key index tham chiếu tới `media_assets`. |
| `asl.lesson_prerequisites` | `idx_prereq_target` | `(prerequisite_lesson_id)` | Foreign key index: Tra cứu bài học này đang là điều kiện tiên quyết của những bài học nào. |
| `asl.sync_mutation_receipts`| `idx_smr_change_seq` | `(user_id, change_sequence)` | Foreign key index: Tăng tốc câu lệnh JOIN trong `receipt()` của `JdbcSyncRepository`. |
| `asl.legal_documents` | `idx_legal_docs_active` | `(document_type, published_at DESC, id DESC)` | Phục vụ trực tiếp câu lệnh `SELECT DISTINCT ON (document_type) ... WHERE published_at <= now()`. |

### 3.2. Sửa đổi Điều kiện Truy vấn để Kích hoạt Index Có sẵn
- **Sửa đổi trong `JdbcDictionaryRepository.java` (Line 34):**
  - Hiện tại: `strpos(search_name, lower(normalize(btrim(?), NFC))) > 0`
  - Chuyển thành: `search_name LIKE '%' || ? || '%'`
  - **Lợi ích:** Kích hoạt lập tức chỉ mục GIN Trigram `idx_sign_search` và `idx_collection_search`.

### 3.3. Tối ưu hóa Batch Query & Loại bỏ N+1
1. **Dictionary:**
   - Thay đổi signature hoặc thêm helper: `Map<UUID, MediaItem> mediaMap = media.refresh(allAssetIds)` trước khi render danh sách.
2. **Practice:**
   - Thay thế việc lặp `content.signs(source)` từng bài học bằng batch query: `SELECT ... FROM asl.lesson_version_signs WHERE lesson_version_id IN (:ids)`.

---

## 4. API & BACKEND OPTIMIZATION PLAN

| Endpoint | Vấn đề hiện tại | Giải pháp tối ưu | Lợi ích dự kiến |
|---|---|---|---|
| `GET /api/v1/config` | Chạy query vào `app_settings` và parse JSON trên mỗi request; transaction `REPEATABLE_READ`. | Cache policy in-memory; bỏ transaction DB hoặc chuyển về `read-only autocommit`. Bật ETag / HTTP Cache-Control. | 0ms DB query, chịu tải hàng chục nghìn req/s lúc khởi động app. |
| `GET /api/v1/lessons/{id}` | Chạy 6 câu query tuần tự nạp toàn bộ exercises/turns/media; cấm cache bằng `no-store`. | Cache toàn bộ object `LessonContent` bất biến theo `versionId` bằng Caffeine. Trả `Cache-Control: public, max-age=86400, immutable`. | Giảm 90% DB load, tải bài học tức thì (< 10ms). |
| `GET /api/v1/signs`<br>`GET /api/v1/collections`<br>`GET /api/v1/search` | Bị N+1 query media assets (101 queries); tìm kiếm không dùng được GIN index. | Gom batch media IDs (1 query duy nhất); sửa câu lệnh tìm kiếm sang toán tử `LIKE` tương thích GIN. | Latency giảm từ 2.200ms xuống còn < 60ms. |
| `POST /api/v1/practice/availability`<br>`POST /api/v1/practice/sets` | Gửi 30 lessons $\rightarrow$ nạp 180 queries tuần tự; tiêu tốn nhiều CPU và connection. | Nạp batch sign IDs theo danh sách `versionId` bằng 1 query; cache snapshot ký hiệu của version. Giới hạn `sources.size() <= 100`. | Latency giảm từ 3–8s xuống < 50ms. |
| `POST /api/v1/sync/mutations` | Gặp rủi ro loop PL/pgSQL trong trigger V5 khi có batch mutation lớn. | Đảm bảo batch size được validate chặt; kiểm tra performance của trigger khi sync nhiều records. | Tránh giữ transaction lock trên `sync_streams` quá lâu. |
| Toàn bộ API xác thực | Gọi ra Google Firebase servers trên mọi request để kiểm tra token revocation. | Thêm In-memory cache 2–5 phút cho kết quả xác thực token hoặc tắt `checkRevoked` cho các read APIs thông thường. | Loại bỏ 100–300ms độ trễ mạng ngoại vi trên mỗi request. |
| Toàn bộ API Public/Read | Bị chặn bởi `synchronized` trên Interceptors. | Thay bằng cấu hình non-blocking rate limiter (Bucket4j / ConcurrentHashMap). | Throughput tăng gấp 10-20 lần trên Multi-core CPU. |

---

## 5. CACHING STRATEGY & UPSTASH REDIS INTEGRATION

### 5.1. Kiến trúc Caching Đa Tầng (Multi-Level Resilient Caching)

Hệ thống được thiết kế theo mô hình **Cache-Aside với cơ chế Fallback an toàn tuyệt đối (Graceful Degradation)**:

```mermaid
flowchart TD
    Client[Client / Mobile App] --> Controller[Spring Controller]
    Controller --> CacheLayer{Cache Manager}
    
    CacheLayer -->|1. Check L1| L1[L1: Caffeine In-Memory Cache]
    L1 -->|Hit| Return[Trả kết quả nhanh 0-1ms]
    
    L1 -->|Miss| RedisCheck{Có REDIS_URL &<br>Redis Up?}
    RedisCheck -->|Có| Redis[L2: Upstash Redis]
    Redis -->|Hit| UpdateL1[Ghi L1 & Trả kết quả 15-30ms]
    
    RedisCheck -->|Không / Lỗi / Timeout| DB[(Neon PostgreSQL)]
    Redis -->|Miss| DB
    DB --> WriteCache[Ghi ngược vào L1 & L2]
    WriteCache --> Return

    subgraph Cơ chế Bảo Vệ (Resilience)
        ErrorHandler[Custom CacheErrorHandler]
        ErrorHandler -.->|Bắt lỗi Redis Timeout/Quota| DB
    end
```

### 5.2. Nguyên tắc "Zero-Downtime & Graceful Degradation" khi không có Redis
> [!IMPORTANT]
> **Yêu cầu bắt buộc từ dự án:** Ứng dụng phải hoạt động hoàn toàn bình thường khi:
> 1. Không cấu hình biến `REDIS_URL` (local dev hoặc staging không có Redis).
> 2. `REDIS_URL` bị sai thông tin hoặc bị rỗng.
> 3. Upstash Redis bị mất kết nối, quá hạn mức (quota exceeded), hoặc mạng chập chờn.

**Giải pháp kỹ thuật:**
1. **Khởi tạo có điều kiện (`@ConditionalOnProperty`):**
   - Nếu `REDIS_URL` có giá trị hợp lệ: Khởi tạo `RedisConnectionFactory` và `RedisCacheManager`.
   - Nếu `REDIS_URL` rỗng hoặc không khai báo: Tự động fallback về `CaffeineCacheManager` (hoặc `SimpleCacheManager` / `NoOpCacheManager`). Server khởi động bình thường mà không văng lỗi `NoSuchBeanDefinitionException`.
2. **Triển khai `CacheErrorHandler` tùy biến:**
   - Trong Spring Cache, mặc định nếu Redis ném ngoại lệ (như `QueryTimeoutException`, `RedisConnectionFailureException`), Spring sẽ văng HTTP 500 ra ngoài controller.
   - Bằng cách ghi đè `CacheErrorHandler`:
     ```java
     public class SafeCacheErrorHandler implements CacheErrorHandler {
         @Override public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
             LOG.warn("Redis unreachable, fallback to DB directly: {}", e.getMessage());
             // Nuốt ngoại lệ để use case tiếp tục gọi DB bình thường
         }
         ...
     }
     ```
   - Nhờ đó, nếu Upstash Redis gặp sự cố, **người dùng không bao giờ gặp lỗi 500**, ứng dụng tự động truy vấn trực tiếp vào Neon Database.

### 5.3. Định dạng Cấu hình `REDIS_URL` cho Upstash
- **Trong file `backend/.env`:**
  ```properties
  REDIS_URL=rediss://default:PASSWORD@ENDPOINT.upstash.io:6379
  ```
  *(Lưu ý: Không dùng dấu nháy kép `""` để tránh Java Properties đọc cả ký tự nháy).*
- **Trong `application.yml`:**
  ```yaml
  spring:
    data:
      redis:
        url: ${REDIS_URL:}
        timeout: 2000ms
        lettuce:
          pool:
            max-active: 16
            max-idle: 8
            min-idle: 2
  ```

---

## 6. SCALABILITY & PRODUCTION READINESS

### 6.1. Hỗ trợ Java 21 Virtual Threads (Project Loom)
- Hiện dự án đang chạy Java 21 trên Spring Boot 4.
- Việc kích hoạt Virtual Threads cực kỳ đơn giản:
  ```yaml
  spring:
    threads:
      virtual:
        enabled: true
  ```
- **Điều kiện tiên quyết bắt buộc:** Phải dọn dẹp các khối `synchronized` trên `CatalogHttpPolicy`, `LearningHttpPolicy`, `MediaHttpPolicy`, `RequestBudget` (như đã phân tích ở Issue #3) để tránh hiện tượng **Carrier Thread Pinning**.
- Khi kích hoạt thành công, mỗi HTTP request xử lý I/O trên Virtual Thread riêng biệt, server có thể duy trì hàng chục nghìn kết nối đồng thời với mức tiêu hao RAM cực thấp.

### 6.2. Sẵn sàng Mở rộng Ngang (Horizontal Scaling)
- **Kiến trúc ứng dụng hiện tại:** Stateless (không lưu Session trong memory). Đây là điểm cộng rất lớn của kiến trúc hiện tại.
- **Rào cản duy nhất:** Bộ rate-limiting đang nằm cục bộ trong RAM của từng tiến trình và chưa hỗ trợ Proxy headers.
- **Giải pháp chuyển tiếp:**
  - Cấu hình `server.forward-headers-strategy: framework` để nhận đúng client IP qua proxy.
  - Khi triển khai cụm nhiều instance, chuyển state rate-limit sang Redis (sử dụng Token Bucket Lua script) hoặc đưa nhiệm vụ rate limit ra Cloudflare / API Gateway.

### 6.3. Phòng vệ DoS và Input Bounding
- Siết chặt tham số mảng đầu vào:
  - `POST /api/v1/practice/availability` và `POST /api/v1/practice/sets`: Giảm giới hạn mảng `sources` từ 10.000 xuống tối đa 50–100 bài học.
  - Giữ vững giới hạn kích thước request payload trong Spring Boot (`spring.servlet.multipart.max-request-size`, `server.tomcat.max-swallow-size`).

---

## 7. PRIORITIZED ROADMAP (LỘ TRÌNH TỐI ƯU HÓA THEO THỨ TỰ ƯU TIÊN)

Roadmap được phân kỳ rõ ràng dựa trên nguyên tắc: **Xử lý các lỗi gây ảnh hưởng trực tiếp và nghiêm trọng nhất trước, tối ưu hóa có rủi ro thấp nhất trước, không làm thay đổi business logic.**

```mermaid
flowchart LR
    P1[Priority 1: Khẩn cấp<br>Fix N+1, Avalanche & Locks] --> P2[Priority 2: Tác động lớn<br>Hikari, GIN Index & Gzip]
    P2 --> P3[Priority 3: Caching & Long-term<br>Caffeine L1 & HTTP Headers]
    P3 --> P4[Priority 4: Mở rộng quy mô<br>Virtual Threads & Redis Limiter]
```

### BẢNG ĐÁNH GIÁ CHI TIẾT TỪNG NHIỆM VỤ

| Phân kỳ | Tên nhiệm vụ | Vị trí can thiệp | Tác động (Impact) | Độ phức tạp (Complexity) | Rủi ro (Risk) | Ưu tiên |
|---|---|---|:---:|:---:|:---:|:---:|
| **P1.1** | **Xóa bỏ N+1 Query trong Dictionary** | `DictionaryService.java` | **CỰC CAO** (Giảm latency 97%) | Thấp | Rất thấp | **P1** |
| **P1.2** | **Loại bỏ N×6 Query trong Practice** | `PracticeService.java`, `LearningPracticeReader.java` | **CỰC CAO** (Tránh sập DB) | Trung bình | Thấp | **P1** |
| **P1.3** | **Gỡ bỏ `synchronized` trên Interceptors** | `*HttpPolicy.java`, `RequestBudget.java` | **CỰC CAO** (Giải phóng luồng) | Thấp | Thấp | **P1** |
| **P2.1** | **Điều chỉnh HikariCP Pool & Timeout** | `application.yml` | **CAO** (Ổn định kết nối DB) | Rất thấp | Rất thấp | **P2** |
| **P2.2** | **Kích hoạt GIN Index cho Search** | `JdbcDictionaryRepository.java` | **CAO** (Search tức thì < 2ms) | Thấp | Thấp | **P2** |
| **P2.3** | **Bật HTTP Compression (Gzip)** | `application.yml` | **CAO** (Giảm 75% băng thông) | Rất thấp | Rất thấp | **P2** |
| **P2.4** | **Thêm Missing Foreign Key Indexes** | Flyway `V7__performance_indexes.sql` | **CAO** (Tối ưu query & join) | Thấp | Rất thấp | **P2** |
| **P2.5** | **Tối ưu Token Verification (Firebase)** | `FirebaseIdTokenVerifier.java` | **CAO** (Giảm 100-300ms auth) | Thấp | Thấp | **P2** |
| **P2.6** | **Tích hợp Upstash Redis (Graceful Fallback)** | `configuration`, Spring Cache | **CAO** (Distributed cache, 0-downtime) | Trung bình | Thấp | **P2** |
| **P3.1** | **Triển khai L1 Cache (Caffeine) kết hợp L2 Redis** | `asl.app_settings`, `LessonContent` | **TRUNG BÌNH** (Offload 80% DB) | Trung bình | Thấp | **P3** |
| **P3.2** | **Cấu hình Cache-Control & ETag** | Web Controllers | **TRUNG BÌNH** (Tối ưu Mobile cache) | Thấp | Thấp | **P3** |
| **P3.3** | **Tối ưu hóa Truy vấn `stats` View** | `JdbcSyncRepository.java`, SQL | **TRUNG BÌNH** (Tăng tốc /me/stats) | Trung bình | Thấp | **P3** |
| **P4.1** | **Kích hoạt Java 21 Virtual Threads** | `application.yml` | **DÀI HẠN** (Scale cực lớn) | Rất thấp | Thấp | **P4** |
| **P4.2** | **Chuyển đổi Distributed Rate Limiting** | Gateway / Redis | **DÀI HẠN** (Multi-instance ready)| Cao | Trung bình | **P4** |

---

## 8. DỮ LIỆU ĐO LƯỜNG HIỆN TẠI (BASELINE BENCHMARK TRÊN RENDER)

Dưới đây là dữ liệu đo lường thực tế trên máy chủ triển khai thật (`https://asl-api-9rr9.onrender.com/`) trước khi thực hiện tối ưu hóa (đo trung bình qua các lượt request):

| Endpoint | HTTP Status | Min (ms) | Avg (ms) | Max (ms) | Nhận định kỹ thuật |
|---|:---:|:---:|:---:|:---:|---|
| `GET /api/v1/config` | 200 | 117 | **280.3** | 592 (Cold 1.754s) | Truy vấn bảng `app_settings` mỗi lần gọi dù dữ liệu tĩnh. |
| `GET /api/v1/home` | 200 | 223 | **310.0** | 419 (Cold 8.881s) | Nạp units và lesson references tuần tự. |
| `GET /api/v1/units` | 200 | 285 | **292.0** | 302 | Quét tuần tự do thiếu partial index active seek. |
| `GET /api/v1/signs` | 200 | 684 | **1.208.3** | 1.639 (Cold 2.383s) | **Bị nghẽn nặng do N+1 query media assets**. |
| `GET /api/v1/collections` | 200 | 192 | **195.3** | 199 | Có correlated count subquery trong SQL. |
| `GET /api/v1/legal-documents` | 200 | 107 | **135.3** | 186 | Dữ liệu tĩnh nhưng vẫn query database mỗi lần. |

---

## 9. KẾT LUẬN & CAM KẾT THỰC HIỆN

1. **Nguyên tắc "Zero-Downtime & Graceful Fallback" với Redis:**
   - Ứng dụng **hoạt động 100% bình thường kể cả khi KHÔNG CÓ REDIS** (chưa truyền `REDIS_URL` hoặc Redis bị ngắt kết nối).
   - Tự động fallback sang In-Memory Cache (Caffeine) hoặc truy vấn trực tiếp DB; không bao giờ crash khi khởi động hay ném lỗi 500 khi Redis gặp sự cố mạng.
2. **Cam kết Đo lường & Viết tài liệu So sánh (Benchmark Docs):**
   - Sau khi hoàn thành tối ưu hóa mã nguồn, hệ thống sẽ thực hiện lại các bài test đo lường trên các endpoint tương ứng.
   - Xuất tài liệu chính thức: `PERFORMANCE_BENCHMARK_REPORT.md` so sánh chi tiết:
     - Thời gian phản hồi Trước vs. Sau (giảm bao nhiêu % hoặc ms).
     - Số lượng câu truy vấn database phát sinh (Roundtrips).
     - Dung lượng gói tin mạng truyền tải.
     - Nếu có endpoint nào thời gian không giảm, sẽ ghi chú phân tích nguyên nhân kỹ thuật cụ thể.
3. **Tuân thủ quy tắc:** Chưa chỉnh sửa mã nguồn cho đến khi nhận được xác nhận chính thức từ bạn.
