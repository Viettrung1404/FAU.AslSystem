# Đối chiếu màn hình ASL Bloom với API mobile

Ngày đối chiếu: **21/10/2026**. Phiên bản contract: **0.3.0**.

## 1. Nguồn và cách đọc

- Contract được đối chiếu: [openapi.mobile.yaml](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml>), trường `info.version = 0.3.0`, 35 operations / 34 paths.
- Thiết kế tham chiếu: [ASL_Bloom_reference_analysis.pdf](<C:/Users/ACER/Downloads/ASL App/ASL_Bloom_reference_analysis.pdf>), 42 trang. Số trang trong tài liệu này là số trang PDF, bắt đầu từ 1.
- PDF trang 8–11: navigation và screen inventory; trang 13–17: hành vi màn hình; trang 32–35: ABC, Review, Conversation và Practice; trang 36–42: thư viện ảnh.
- Các mã `Sxx` là mã bằng chứng trong PDF, không phải tên route hoặc API.

**Mục đích:** giúp nhóm Flutter/backend biết màn nào lấy dữ liệu ở đâu, lúc nào gọi API, thao tác nào xử lý local và phần nào chưa có contract tương ứng. Đây là đối chiếu thiết kế với contract, không phải kết quả kiểm thử backend đang chạy.

**Thứ tự áp dụng:** dùng file YAML được chỉ định để xác định API hiện tại; dùng PDF để xác định màn hình/hành vi tham chiếu. Khi hai nguồn khác nhau, ghi rõ khác biệt; không tự thêm endpoint hoặc coi hành vi PDF là API đã tồn tại. Không dùng kết luận về v0.2 thay cho nội dung v0.3.0 này.

PDF có quan sát từ cả bản chính thức và bản mod. Những màn mở khóa ở trang 32–35 giúp tham khảo UI nhưng không chứng minh chính sách Premium/free của bản chính thức. Onboarding tạo tài khoản mới, giao dịch mua thật và một số thao tác chỉ được quan sát một phần.

Quy ước:

- Các đường dẫn API bên dưới đều ghi đầy đủ `/api/v1`.
- **Public:** không bắt buộc đăng nhập. **Bearer:** cần access token. **Conditional:** quyền phụ thuộc tài nguyên; xem ghi chú.
- **Local:** Flutter đọc/ghi dữ liệu trên thiết bị, không có nghĩa là đã hỗ trợ học offline. `offlineLearningSupported = false`.
- **Gợi ý triển khai:** cách tổ chức lời gọi đề xuất trong tài liệu này, không phải một endpoint hay yêu cầu sản phẩm mới.
- `x-phase` là nhãn giai đoạn trong contract, không chứng minh endpoint đã hoặc chưa được triển khai trong source backend.

## 2. Nguyên tắc chung

1. Nội dung học, sign và catalog được đọc bằng API public. Dữ liệu cá nhân như attempt, favorites, collection riêng và preferences được lưu local; tài khoản bổ sung khả năng sync.
2. Một lesson được tải nguyên bộ qua `GET /api/v1/lessons/{lessonId}`. Chọn đáp án, Check, Next, ghép cặp, ôn sai không cần một API cho mỗi thao tác.
3. Home trả catalog, không trả toàn bộ dashboard cá nhân. Flutter ghép điểm, streak, số sign đã học và trạng thái node từ local store; tài khoản có `GET /api/v1/me/stats` để đối chiếu số liệu đã sync.
4. Practice dùng `completedLessons: [{lessonId, versionId}]`. Không gửi trường cũ `completedLessonIds` vào hai API Practice.
5. Premium hiện mở nội dung các catalog collection Premium. Lesson, sign riêng, Practice từ lesson và collection cá nhân không có Premium gate trong contract này.
6. Dữ liệu đã có trong response/cache có thể tái sử dụng. Không cần gọi API detail chỉ vì người dùng vừa chuyển màn hình nếu dữ liệu cần hiển thị đã đầy đủ và còn dùng được.

## 3. Khởi động, Welcome và đăng nhập

Tham chiếu PDF: trang 8, 10–11, 15, 25; ảnh trang 36 và 42.

| Màn hình / thao tác | Lúc gọi | API và dữ liệu | Xử lý ở Flutter |
|---|---|---|---|
| Splash — S00 | Bootstrap ứng dụng, theo chính sách cache của app | `GET /api/v1/config` — Public; lấy cấu hình học, Practice, scoring và `supportUrl` | Animation, đọc local store và phiên đăng nhập, chọn màn tiếp theo. Không có API splash riêng. |
| Welcome — S01 | Khi cần hiển thị Terms/Privacy và lấy ID tài liệu | `GET /api/v1/legal-documents` — Public; `LegalDocuments.items` chứa type, version, URL, documentId | Hiển thị checkbox và lưu những tài liệu người dùng thực sự đồng ý. |
| Bấm Terms / Privacy — S02/S03 | Khi bấm liên kết; dùng URL đã tải | Không cần API mới; mở `LegalDocument.url` | Mở trình duyệt/webview. Không tái hiện lỗi reference dẫn vào Home. |
| Get started / onboarding sau Welcome | Khi đi tiếp | Chưa có API questionnaire/onboarding riêng. Luồng email dùng các API dưới đây nếu thiết kế chuyển sang đăng nhập/tạo tài khoản | PDF chưa khảo sát đầy đủ onboarding; không tự coi các câu hỏi onboarding là đã được thiết kế. |
| Email login / register — S41/S71 | Bấm Continue sau khi nhập email hợp lệ | `POST /api/v1/auth/otp/request`; body `email`, `clientRequestId`; nhận `OtpChallenge` | Giữ `challengeId`, `expiresAt`, `resendAfterSeconds`, chuyển sang nhập mã. |
| Access Code — S42 | Bấm xác nhận mã | `POST /api/v1/auth/otp/verify`; body gồm `challengeId`, `code`, `clientRequestId`, `timeZone`, `acceptedDocumentIds`; `deviceLabel` tùy chọn | Lưu phiên từ `TokenPair`, chuyển sang luồng đồng bộ. Mã hiện tại 4 số. |
| Resend code | Người dùng bấm gửi lại sau thời gian chờ | `POST /api/v1/auth/otp/challenges/{challengeId}/resend` | Cập nhật challenge/timer theo response; mã cũ bị vô hiệu theo mô tả contract. |
| Token hết hạn | Lớp quản lý phiên cần gia hạn | `POST /api/v1/auth/refresh`; body `refreshToken`, `clientRequestId` | Lưu token mới. Đây là tác vụ dùng chung, không phải màn hình riêng. |

Đăng nhập email hợp lệ lần đầu có thể tạo account theo mô tả OpenAPI; không có endpoint register riêng. Không tự động đánh dấu đã đồng ý tài liệu chỉ vì gọi verify.

## 4. Home, lộ trình và lesson

Tham chiếu PDF: trang 8–9, 13–14, 17, 32–33; ảnh trang 37–39.

### 4.1. Home và unit

| Màn hình / thao tác | API | Response cần dùng / trách nhiệm UI |
|---|---|---|
| Vào Home — S10 | `GET /api/v1/home` — Public | `Home.units.items` chứa unit và các `LessonSummary`; `Home.units.nextCursor` dùng tải tiếp. |
| Cuộn tải thêm lộ trình | `GET /api/v1/home?cursor=...&limit=...` | Ghép trang mới vào catalog hiện có. Không tải nội dung tất cả lesson khi chỉ xem lộ trình. |
| Repository muốn lấy danh sách unit trực tiếp | `GET /api/v1/units` — Public | Cách lấy catalog thay thế; response `Units.items` và `nextCursor`. Không bắt buộc gọi thêm sau `/home`. |
| Cần tải riêng một unit | `GET /api/v1/units/{unitId}` — Public | Chỉ gọi khi chưa có hoặc cần tải lại unit đó; PDF không yêu cầu một màn Unit detail riêng. |
| Stats row — S11/S13/S14 | Local; tài khoản có thể đối chiếu bằng `GET /api/v1/me/stats` | Hiển thị tổng điểm, streak, số sign đã học. Chạm các chỉ số trong reference không mở màn mới. |
| Sticky header, màu/icon unit, divider, mascot, nút lên đầu | Không có API riêng | `Unit.description` giao Flutter cấu hình section/theme/icon/decorations theo ID ổn định và có fallback khi gặp unit mới. |

**Quy tắc mở lesson của file này:** đọc `LessonSummary.prerequisiteLessonIds`. Mảng rỗng nghĩa là được bắt đầu, không phải đã hoàn thành. Trong development, catalog mẫu trả mảng rỗng cho mọi lesson; Flutter không tự khóa bằng vị trí hoặc số lesson đã hoàn thành. Khi prerequisites được bật và trả về các ID, UI kiểm tra đủ các bài đó đã hoàn thành local. HTTP lesson vẫn là public, không dùng prerequisites để phân quyền.

Trạng thái vàng/completed và điểm chỉ đến từ tiến độ thật, không đến từ việc mọi node đang mở trong development.

### 4.2. Player, kết quả và resume

| Màn hình / thao tác | API | Dữ liệu và xử lý |
|---|---|---|
| Mở lesson thường — S80–S95 | `GET /api/v1/lessons/{lessonId}` | Nhận `LessonContent`: lessonId, versionId, type, signs, exercises, conversationTurns, scoringPolicy. Tạo attemptId local cho lượt mới. |
| Mở ABC — S106/S107 hoặc Review node — S112/S113 | Cùng API lesson | Phân biệt bằng `LessonContent.type = ALPHABET` hoặc `REVIEW`; không có API `/alphabet` hay `/review` riêng. |
| Chọn câu trả lời, Check, Next | Không gọi API chấm từng câu | Dùng `answerKey`; lưu câu trả lời đầu, feedback, bước hiện tại và điểm tạm trong checkpoint. |
| Ghép cặp | Không gọi API từng cặp | Dùng mapping đáp án, giữ cặp đã ghép đúng; mỗi cặp được tính điểm một lần trong attempt. |
| Tip / encouragement / review intro / praise | Không có API riêng | Render các bước trong lesson; `REVIEW_INTRO` hiện khi còn câu phải ôn. |
| Ôn câu sai | Không có API riêng | Duy trì review queue, ôn tới khi đúng; review không cộng điểm. |
| Bấm X thoát — S84 | Lưu local ngay; nếu đã đăng nhập, đưa thay đổi attempt vào hàng đợi `POST /api/v1/sync/mutations` | Giữ `attemptId`, `versionId`, checkpoint. X để resume không đồng nghĩa với tự đánh dấu `ABANDONED`. |
| Mở lại bài dở | `GET /api/v1/lessons/{lessonId}?versionId={savedVersionId}` khi cần tải nội dung | Khôi phục checkpoint đúng phiên bản. Không trộn bài mới nhất với đáp án đã làm ở bản cũ. |
| Kết quả — S90/S95 | Không có API `/complete` riêng; sync bằng mutation `LESSON_ATTEMPT` khi có account | Hoàn tất nội dung và ôn sai rồi ghi `COMPLETED`; UI hiển thị kết quả local ngay. Backend tính lại điểm khi nhận sync. |
| Streak — S91 | Không có API riêng | Dựa trên ngày hoàn thành và dữ liệu hoạt động local; tài khoản đối chiếu stats sau sync. Quy tắc chỉ hiện lần hoàn thành đầu ngày lấy từ PDF. |
| Chọn mục tiêu — S92 | Lưu local; `POST /api/v1/sync/mutations` với kind `PREFERENCES` khi đăng nhập | `learningGoalDays` trong schema là chuỗi: `"7"`, `"14"`, `"30"`, `"50"`. |

**Điểm:** `ScoringPolicy.commitOn = LESSON_COMPLETED`. Attempt dở có `earnedPoints = 0`, điểm tạm nằm ở `checkpoint.temporaryPoints`. Đây là khác biệt với PDF trang 14, nơi thoát dở đã tăng điểm tổng. Replay toàn bài dùng attemptId mới và có thể nhận điểm; retry sync cùng attempt không phải một lần học lại.

**Conversation — S127:** dùng cùng API lesson, `type = CONVERSATION`, `exercises` rỗng và đọc `conversationTurns`. Flutter dùng `speakerSide`, `video`, `glossCaption`, `englishTranslation`, `position` để dựng chat. Continue nối lượt tiếp theo và cuộn local; không gọi API từng lượt. Lưu tiến độ qua `LessonAttempt`/sync như lesson. Không có camera/AI scoring trong contract.

### 4.3. Các dạng exercise dùng chung một lần tải lesson

| Giao diện trong PDF | `Exercise.type` | Dữ liệu / hành vi chính |
|---|---|---|
| Video → chọn từ, E1 | `VIDEO_TO_WORD` | Video, lựa chọn chữ, đáp án lựa chọn |
| Từ → chọn video, E2 | `WORD_TO_VIDEO` | Từ cần tìm, lựa chọn video |
| Video → chọn hình, E3 | `VIDEO_TO_IMAGE` | Video, hình và caption |
| Ghép video với từ, E4 | `MATCH_PAIRS` | Cột trái/phải và mapping cặp đúng |
| Điền chỗ trống, E5 | `FILL_GAP` | Câu có vị trí trống, token bank, đáp án |
| Xếp đúng thứ tự, E6 | `ORDER_SIGNS` | Token bank và thứ tự token đúng |
| Video → chọn nghĩa, E7 | `VIDEO_TO_MEANING` | Lựa chọn nghĩa nằm trong payload bài tập |
| Câu hỏi kiến thức, E8 | `KNOWLEDGE` | Câu hỏi và lựa chọn kiến thức |
| Đánh vần — trang 33 | `SPELL` | Các tile và thứ tự đáp án; không gộp tile chỉ vì có chữ giống nhau |
| Thẻ mẹo, I1 | `TIP` | Nội dung mẹo và media nếu có |
| Khích lệ, I2 | `ENCOURAGEMENT` | Nội dung bước khích lệ |
| Giới thiệu ôn sai, I3 | `REVIEW_INTRO` | Hiện theo trạng thái review |
| Khen ngợi, I4 | `PRAISE` | Nội dung bước khen ngợi |

Mọi dạng trên được lấy từ `LessonContent.exercises`, render theo `type`, `payload`, `media`, `prompt`, `position`. Không dùng số điểm mẫu làm hằng số; lấy `pointsPerCorrect` từ nội dung.

## 5. Practice

Tham chiếu PDF: S20–S24 ở trang 8, 10, 14; S129–S135 ở trang 32–35; ảnh trang 40.

### 5.1. Tab Practice và điều kiện mở

Khi vào tab hoặc sau khi tập bài đã hoàn thành thay đổi, gọi `POST /api/v1/practice/availability` với `completedLessons` lấy từ các attempt **COMPLETED**, có cả lessonId và versionId. Người chưa học gửi `{"completedLessons": []}` là hợp lệ cho availability.

| Trạng thái response của mode | UI cần hiển thị |
|---|---|
| `unlocked = false`, reason `NEED_MORE_LESSONS` | Sheet cần học thêm — S21/S24; nút Go to lessons chỉ đổi sang Home, không gọi API riêng |
| `unlocked = true`, `canGenerate = false`, reason `INSUFFICIENT_CONTENT` | Thông báo chưa đủ sign phù hợp cho mode; không gắn nhầm khóa Premium |
| `unlocked = true`, `canGenerate = true` | Cho bắt đầu mode bằng API sinh set |

Cả bốn mode mở sau **3 lessonId khác nhau** đã hoàn thành. Hai phiên bản hoàn thành của cùng một lesson chỉ được tính là một lesson, nhưng nguồn sign là hợp của các phiên bản được gửi. Việc mọi node mở trong development không tự mở Practice.

### 5.2. Bắt đầu và chạy từng mode

Tất cả dùng `POST /api/v1/practice/sets`, Public:

| Màn hình | Giá trị `mode` | Response item / thao tác local |
|---|---|---|
| Quiz — S130–S132 | `QUIZ` | `QuizPracticeItem` phân loại theo `type`; Check, feedback, Next được chấm local |
| Flashcards — S133 | `FLASHCARDS` | `FlashcardPracticeItem.sign`; mặt trước tên, mặt sau video; Show me và 3 mức nhớ xử lý local |
| Sign Matching — S134 | `SIGN_MATCHING` | `MatchingPracticeItem.left/right/correctPairs`; ghép, hiệu ứng đúng/sai, bộ đếm local |
| Alphabet Matching — S135 | `ALPHABET_MATCHING` | Cấu trúc matching tương tự, nguồn chỉ là chữ cái đã học |

Ví dụ request lấy trực tiếp từ fixture v0.3.0; thay UUID bằng dữ liệu người dùng thực sự hoàn thành:

```json
{
  "mode": "QUIZ",
  "itemCount": 10,
  "completedLessons": [
    {"lessonId": "e5a6e571-c0f4-50c1-a18b-bee8c717bc1b", "versionId": "ce7ce6d0-e0b5-56aa-ba21-78df0d0a5e71"},
    {"lessonId": "9b25761e-601b-59bf-9049-7589b90f8de4", "versionId": "204e7b8d-a008-577d-a809-13bf2375d615"},
    {"lessonId": "93b268d7-6261-5659-ab01-852dec96c54c", "versionId": "c7322ce1-c690-5419-bef5-e5ecccf40f2c"}
  ]
}
```

Response gồm `setId`, `mode`, `sourceLessons`, `items`, `generatedAt`, `expiresAt`, `awardsLessonPoints`, `countsForStreak`. `itemCount` mặc định 10, tối đa 30; matching tính theo **vòng**, mỗi vòng 2–8 cặp. Có thể nhận ít item hơn yêu cầu nếu nội dung không đủ.

Quiz hiện hỗ trợ:

- `VIDEO_TO_WORD`: `payload.video`, lựa chọn chữ.
- `WORD_TO_VIDEO`: `payload.word`, lựa chọn video.
- `VIDEO_TO_IMAGE`: video và lựa chọn hình có mapping biên tập sẵn.
- `payload.layout = GRID_2X2` chỉ khi đủ 4 lựa chọn; `LIST` có 2–4 lựa chọn. Không ép mọi câu thành lưới 4 ô.
- Chấm bằng `answerKey.correctOptionId`. Quiz có cấu trúc riêng; không giả định mọi field của exercise trong lesson đều xuất hiện trong Quiz.

Flutter giữ set đang dùng; không có API fetch lại set bằng setId hoặc API nộp kết quả Practice. Retry sinh set có thể trả bộ khác, nên không gọi lại mỗi lần chuyển câu. `expiresAt` là mốc refresh media, không phải hạn cấm báo lỗi.

Kết quả Practice được trình bày local. `awardsLessonPoints = false`, `countsForStreak = false`: không cộng vào tổng điểm lesson hoặc streak. PDF có ô Points/Score ở kết quả Quiz và accuracy ở matching; **công thức điểm riêng của Practice/accuracy chưa được mô tả đầy đủ trong file này**, cần thống nhất trước khi giữ nguyên những ô số đó.

## 6. Signs, tìm kiếm, collections và chia sẻ

Tham chiếu PDF: trang 8, 10–11, 14–16, 32–34; ảnh trang 41.

| Màn hình / thao tác | API | Dữ liệu và xử lý UI |
|---|---|---|
| Signs → Public — S30/S137 | `GET /api/v1/collections` — Public | Render lưới collection bằng name, cover, signCount, isPremium. Không dùng `/signs` thay cho lưới collection. |
| Chạm collection — S32/S140 | `GET /api/v1/collections/{collectionId}` khi chưa đủ dữ liệu | Giới thiệu public, kể cả collection Premium; có `shareUrl`, chưa có danh sách thành viên. |
| Danh sách sign trong collection | `GET /api/v1/collections/{collectionId}/signs` — Conditional; hỗ trợ cursor/limit | Free cho khách; Premium cần quyền. Response `Signs` đã chứa tên và video từng sign. |
| Chạm collection khóa — S31 | Dùng `isPremium` và entitlement đã biết để chọn UI; API danh sách thành viên vẫn là nơi kiểm tra quyền | Khách cần đăng nhập khi đi tới mua; tài khoản chưa có quyền tới paywall. Không suy ra quyền chỉ từ icon khóa. |
| Chi tiết sign — S33 | `GET /api/v1/signs/{signId}` nếu chưa có dữ liệu | `Sign` hiện chỉ có id, name, video. Có thể dùng trực tiếp sign nhận từ collection/search/lesson. |
| Next/Previous sign | Dùng danh sách đang xem; tải trang collection tiếp theo khi cần | Không có API next-sign/previous-sign. App giữ thứ tự danh sách và cache các trang đã duyệt. |
| Turtle / slow playback / replay | Không có API | Điều khiển player local. |
| Tìm kiếm — S60 | `GET /api/v1/search?q=hello&type=ALL` — Public; type có ALL/SIGN/COLLECTION | Phân nhóm response item theo type. Gợi ý debounce và bỏ response cũ khi người dùng đổi query. Query rỗng sau trim không gửi, vì có thể nhận 422. |
| Chạm sign trong search — S61 | Có thể mở video từ item.sign ngay; chỉ GET detail khi cần | Giữ context search khi Back, không tái hiện lỗi nhảy sang tab cũ. |
| Kết quả collection trong search — S62 | Dùng API collection detail và collection signs như trên | API tìm catalog collection; collection riêng được tìm và ghép local. Không có tìm kiếm public collection cá nhân của người khác. |
| My collections — S138 | Đọc local store; dữ liệu account đến từ sync | Không có `GET /me/collections` riêng. |
| Create collection — S139 | Tạo UUID và lưu local; account gửi mutation `PERSONAL_COLLECTION` | `PersonalCollection` có id, name, signIds, deleted. Rỗng được phép; không cần Premium. |
| Đổi tên / thêm / xóa / sắp xếp sign trong collection riêng | Local rồi `POST /api/v1/sync/mutations` | signIds biểu diễn thứ tự; gửi resource theo schema, không có endpoint sắp xếp riêng. |
| Bộ chọn sign để thêm collection — màn bổ trợ đề xuất | `GET /api/v1/signs` hoặc `/api/v1/search` | PDF chỉ mở form tạo collection, chưa xác nhận đầy đủ flow thêm sign; đây là cách dùng API sẵn có. |
| Favorites — S36 và tab Favorites | Local; account sync kind `FAVORITE` | targetType SIGN/COLLECTION, targetId, isFavorite. Thiếu tên/media thì lấy detail tương ứng; schema sync chỉ chứa ID/trạng thái. |
| Share catalog collection — S35 | Không cần API share mới | Copy `Collection.shareUrl` hoặc mở native share sheet với URL đó. Lấy collection detail nếu chưa có URL. |
| Mở shared link | App/web xử lý link dạng `/collections/{id}`, rồi dùng API collection | Share URL không cấp quyền Premium. Web landing page và verified app links cần được triển khai riêng. |
| Practice từ collection/sign — S37/S39/S141; upsell S38 | **Chưa có contract theo nguồn collection/sign** | `PracticeRequest` chỉ nhận completedLessons, không có collectionId/signId/sourceType. Không dùng API hiện có rồi quảng cáo rằng đang luyện đúng collection đó. |

Phân biệt quan trọng: `/collections/{id}` dùng cho **catalog collection**. ID của `PersonalCollection` local không được giả định là ID có thể lấy bằng endpoint catalog này. Collection riêng được dựng từ local `signIds` và dữ liệu sign tương ứng.

`shareUrl` chỉ có cho catalog collection, không có trong `PersonalCollection`. Mua Premium cũng không thay đổi việc nguồn Practice hiện chỉ là lesson đã hoàn thành.

## 7. Profile, cài đặt và hỗ trợ

Tham chiếu PDF: trang 9, 11, 15, 17, 34; ảnh trang 42.

| Màn hình / thao tác | API | Dữ liệu và lưu ý |
|---|---|---|
| Profile khách — S70 | Không gọi `/me`; legal/config nếu chưa có | Log in/Register tới luồng OTP. Thống kê khách nếu app hiển thị thì lấy local. |
| Profile đăng nhập — S72/S136 | `GET /api/v1/me` — Bearer | Hiển thị tên/email từ Profile; TokenPair.user có thể cung cấp dữ liệu ban đầu. |
| Your progress | Local trước, `GET /api/v1/me/stats` để đối chiếu khi phù hợp | totalPoints, currentStreak, recordStreak, learnedSignIds, completedLessonIds, activityDates. Stats phản ánh attempts đã được nhận, không nhất thiết bao gồm outbox local chưa sync. |
| Sửa tên | `PATCH /api/v1/me` — Bearer; body `{ "name": "..." }` | Không dùng endpoint này để sửa email/preferences. |
| Daily reminders / Haptics | Local rồi mutation `PREFERENCES` | Nhắc giờ và rung do thiết bị thực hiện. Sync preferences không tự cấp quyền notification của OS. |
| Đổi mục tiêu hoặc giờ nhắc | Mutation `PREFERENCES` khi đăng nhập | Gửi đủ schema preferences; `dailyReminderLocalTime` dạng HH:mm hoặc null; timezone IANA. |
| Help Center — S74 | Mở `Config.supportUrl` | Không có API ticket/FAQ riêng trong contract. |
| Terms / Privacy | `GET /api/v1/legal-documents` nếu chưa có, mở URL | Dùng chung với Welcome. |
| Logout | `POST /api/v1/auth/logout` với refreshToken | Hủy phiên local và chuyển về dữ liệu guest. Không hiển thị dữ liệu account A cho account B. Tách namespace local là gợi ý triển khai cần giữ nhất quán với sync. |
| Delete account | **Không có operation tương ứng trong file** | Thiết kế hiện có nút trong PDF; cần bổ sung flow/API hoặc chưa hiển thị chức năng hoạt động. |
| Discount code — S73 | **Không có operation tương ứng trong file** | Không ánh xạ sang purchase verify/restore. |

## 8. Premium, mua và khôi phục

Tham chiếu PDF: trang 9, 11, 15, 17, 32; ảnh trang 42. PDF chưa chạy giao dịch mua/restore thật; thứ tự API dưới đây dựa vào OpenAPI.

| Màn hình / hành động | API và thứ tự | Kết quả cần xử lý |
|---|---|---|
| Quyền Premium hiện tại | `GET /api/v1/me/entitlements` — Bearer | premiumCollections, validUntil, checkedAt. Khách không gọi endpoint Bearer. |
| Profile → Subscription | `GET /api/v1/me/subscriptions` — Bearer | Danh sách subscription để hiển thị và xác định subscriptionId khi quản lý. |
| Free vs Premium — S43 | Nội dung so sánh ở UI; dùng entitlement nếu đã đăng nhập | Nội dung phải phù hợp quyền PREMIUM_COLLECTIONS hiện tại, không sao chép lời quảng cáo quota/Practice Premium từ PDF. |
| Plans — S44 | `GET /api/v1/subscription/products?store=GOOGLE_PLAY` hoặc `APP_STORE` — Public; sau đó query Store SDK theo product | Backend trả productId/basePlanId/period; giá bản địa hóa và trial lấy từ Store SDK, không hardcode giá PDF. |
| Bắt đầu mua | Đăng nhập → `GET /api/v1/me/billing-context` → Store SDK | Gắn giao dịch với account bằng appAccountToken hoặc googleObfuscatedAccountId phù hợp Store. |
| Sau khi Store trả bằng chứng mua | `POST /api/v1/purchases/verify` — Bearer; body PurchaseProof, header `Idempotency-Key` | HTTP 202 chưa đồng nghĩa có quyền. Đọc status trong PurchaseStatus. |
| Đang xác minh | `GET /api/v1/purchases/{purchaseId}` — Bearer | Poll khi PENDING, dừng khi terminal. VERIFIED mới cấp quyền theo contract. Gợi ý backoff khi poll. |
| Restore purchase | Đăng nhập → Store SDK lấy proofs → `POST /api/v1/purchases/restore` — Bearer, `Idempotency-Key` | Xử lý từng status; poll các purchaseId pending. Không chỉ bật Premium local khi người dùng bấm Restore. |
| Manage / Cancel subscription | `GET /api/v1/subscriptions/{subscriptionId}/management-link` — Bearer | Mở URL quản lý của Store. Không có DELETE subscription riêng trong mobile API. |

Sau VERIFIED/restore thành công, dùng entitlement mới để cập nhật UI và tải lại `GET /api/v1/collections/{collectionId}/signs` nếu người dùng đang chờ mở collection. Backend vẫn kiểm tra quyền mỗi trang.

Giao dịch retry giữ nguyên Idempotency-Key và body theo contract. Không đưa entitlement vào sync/mutations; quyền phải đến từ Store được backend xác minh.

## 9. Báo lỗi nội dung và media

### 9.1. Report — S83 và cờ báo lỗi Quiz

`POST /api/v1/reports` cho phép khách gửi, token tùy chọn. Body chung bắt buộc: `clientReportId`, `targetType`, `targetId`, `reason`, `message`.

| Nơi báo lỗi | targetType / context |
|---|---|
| Sign detail | SIGN, targetId = signId |
| Lesson nói chung | LESSON, targetId = lessonId |
| Câu hỏi lesson / feedback — S83 | EXERCISE, targetId = exerciseId, bắt buộc lessonVersionId |
| Catalog collection | COLLECTION, targetId = collectionId |
| Câu Quiz — PDF trang 35 | PRACTICE_ITEM, targetId = item.id, bắt buộc practiceContext |

`practiceContext` chứa `setId`, `mode = QUIZ`, `sourceLessons`, `generatedAt` và nguyên `item` đã gặp. Giữ assetIds, option IDs, answerKey; không sinh lại Quiz để dựng report. `lessonVersionId` không gửi cho PRACTICE_ITEM; `practiceContext` không gửi cho các target khác. Chế độ report PRACTICE_ITEM hiện chỉ mô tả Quiz, không tự áp dụng cho Flashcards/Matching.

`reason` nhận WRONG_CONTENT / VIDEO_PROBLEM / WRONG_ANSWER / OTHER. PDF chỉ mô tả menu 3 lý do và Cancel, chưa thử submit; contract yêu cầu thêm `message` không rỗng. UI cần có cách nhập/chuẩn bị message rõ ràng trước khi gửi. Đây là chỗ cần hoàn thiện flow, không phải API đã thiếu.

Retry giữ nguyên clientReportId và body; cùng ID nhưng đổi body nhận 409 REPORT_ID_REUSED.

### 9.2. Media

Video/ảnh được tải trực tiếp từ URL trong response. `POST /api/v1/media/urls` nhận `assetIds` để làm mới URL khi hết hạn. Không gọi API này cho mỗi lần Play hoặc Next nếu URL còn dùng được.

Request refresh thất bại toàn bộ nếu có asset không khả dụng theo mô tả endpoint. UI cần phân biệt hết hạn URL với nội dung bị thu hồi; không retry vô hạn cho nội dung đã mất. URL `.invalid` trong examples là placeholder, không chứng minh hệ thống media đã hoạt động.

## 10. Đồng bộ dùng chung cho các màn hình

Đây là tác vụ nền của ứng dụng, không phải một tab mới. Endpoint liên quan đều Bearer:

- `GET /api/v1/sync/snapshot`: lấy trạng thái account theo các trang cùng watermark.
- `GET /api/v1/sync/changes?since=...`: lấy thay đổi sau cursor đã lưu.
- `POST /api/v1/sync/mutations`: đẩy các thay đổi local.
- `GET /api/v1/me/stats`: đối chiếu thống kê account đã được server nhận.

| Loại dữ liệu | kind của mutation | Màn hình tạo thay đổi |
|---|---|---|
| Attempt học dở/hoàn thành | LESSON_ATTEMPT | Lesson, ABC, Review, Conversation |
| Thích/bỏ thích | FAVORITE | Sign, collection, Favorites |
| Collection riêng | PERSONAL_COLLECTION | My collections và editor |
| Mục tiêu, reminder, haptics, timezone | PREFERENCES | Goal picker, Profile/settings |

**Trình tự tích hợp đề xuất:** sau OTP verify, đọc hết snapshot → gộp với dữ liệu local và giữ hàng đợi chưa gửi → push mutations → pull changes từ cursor đã lưu → lấy stats để đối chiếu. Đây là trình tự orchestration; YAML không chứa đầy đủ mọi thuật toán merge xung đột của từng entity và có dẫn sang SYNC_PROTOCOL.md. Nhóm cần thống nhất tài liệu sync cùng phiên bản khi triển khai.

Những điều đã nêu trực tiếp trong YAML:

1. Snapshot có nextCursor ở trang giữa; syncCursor chỉ có ở trang cuối. Áp dụng đủ trang rồi mới lưu watermark.
2. Một batch tối đa 100 mutations, một mutation cho mỗi entity; batch atomic.
3. Mỗi mutation có mutationId, kind, entityKey, baseRevision, data. Tạo mới dùng baseRevision 0; sửa dùng revision hiện tại.
4. Retry cùng mutationId/body được dedupe; không đổi nội dung dưới cùng ID khi retry.
5. Xung đột revision: pull/rebase rồi tạo mutationId mới, không tiếp tục gửi đè revision cũ.
6. Cursor quá hạn 410: lấy snapshot mới nhưng giữ pending local mutations.
7. Push không trả delta cursor; không thay thế bước pull bằng việc coi response push là toàn bộ dữ liệu account.

Gợi ý lịch sync: local ghi ngay sau thao tác; account đẩy theo batch khi hoàn thành/thoát lesson, thay đổi favorites/collection/preferences và khi kết nối lại. Không cần gửi HTTP ở mỗi lần chọn đáp án. Chưa đăng nhập thì giữ local, không gọi endpoint Bearer.

## 11. Những điểm khác hoặc còn thiếu so với PDF

### 11.1. Những nhận xét cũ đã được xử lý trong file này

| Nội dung | Trạng thái v0.3.0 |
|---|---|
| Practice không biết phiên bản đã học | Đã có CompletedLessonVersion và completedLessons; không tự thay bằng version mới nhất |
| Quiz chỉ có video → chữ | Đã có VIDEO_TO_WORD, WORD_TO_VIDEO, VIDEO_TO_IMAGE và LIST/GRID_2X2 |
| Không có ngữ cảnh báo lỗi Quiz | Đã có PRACTICE_ITEM và PracticeReportContext |
| Home thiếu phân công theme/section | Unit.description đã giao Flutter cấu hình presentation theo ID |
| Share collection thiếu link | Collection.shareUrl đã bắt buộc; mô tả app/universal links và web landing |
| Fixture ABC sai type; yearly sai period | Fixture Home đã là ALPHABET; product yearly đã là YEARLY |

Các điểm trên không còn là thiếu sót giống bản v0.2. Có schema/contract không đồng nghĩa backend và Flutter đã triển khai xong.

### 11.2. Các phần cần đổi UI hoặc bổ sung quyết định

| Màn hình / vấn đề | PDF | Contract hiện tại | Việc cần làm |
|---|---|---|---|
| Daily-limit wall — S98 | Có quota 2 lesson/ngày | Config.dailyLessonLimit luôn null | Không áp dụng quota và màn chặn này theo contract hiện tại |
| Node khóa Home | Có node current/locked; quy tắc mở chưa hoàn toàn được quan sát | Development examples mở mọi node, prerequisites rỗng | Không khóa bằng position; production dùng đúng prerequisites được trả |
| Điểm khi thoát bài | Tăng ngay dù học dở | Commit tổng điểm khi completed | UI phân biệt điểm tạm và tổng điểm; không cộng dở vào totalPoints |
| Practice trong collection/sign — S37/S39/S141 | Có nút và sheet chọn mode | Không có nguồn collection/sign trong PracticeRequest | Bỏ/đổi flow này hoặc mở rộng contract trước khi bật; không gắn nhãn sai nguồn luyện |
| Kết quả Quiz và accuracy Matching | Có Points/Score và accuracy | Không thưởng lesson points/streak; chưa định nghĩa rõ công thức chỉ số riêng Practice | Chốt cách tính/hiển thị các chỉ số local nếu giữ UI PDF |
| Caption sau Check của Quiz chọn video | PDF trang 33/35 có caption ở các video | QuizVideoOption chỉ có id, signId, video; không có label | Tra tên từ snapshot nguồn đã tải hoặc bổ sung label có phiên bản; không giả định response option đã có caption |
| User-made collections trong search — S62 | Có kết quả collection do người dùng tạo | Search catalog + collection riêng local | Đổi tên/ý nghĩa nhóm kết quả; không hứa tìm collection cá nhân của người khác |
| Paywall — S43 | Quảng cáo unlimited lessons, Practice, personalized collections | Premium chỉ mở catalog collections Premium | Viết lại quyền lợi; không dùng nguyên copy của reference |
| Delete account / discount — S73 | Có nút/màn trong Profile | Chưa có operation tương ứng | Bổ sung flow/API hoặc chưa bật chức năng |
| Sign meaning | Sign detail có nhãn từ; bài tập có lựa chọn nghĩa | Sign vẫn chỉ id/name/video, chưa có meaning | Nếu muốn nghĩa giải thích/bản dịch riêng, đây là mở rộng cần chốt; nghĩa đáp án exercise lấy từ payload, không suy ra mọi câu từ Sign.name |
| Share collection riêng | Chưa khảo sát đầy đủ việc tạo/chia sẻ | PersonalCollection không có shareUrl, private | Không dùng link catalog cho ID collection riêng |
| Goal chưa chọn nhưng đổi haptics/reminder | Có trạng thái chưa chọn mục tiêu ở S92 | Preferences yêu cầu learningGoalDays, không nhận null | Chốt giá trị khởi tạo hoặc thay schema; không tự coi example "7" là quyết định người dùng |
| Onboarding mới, camera, offline | Chưa khảo sát đầy đủ / không tìm được entry | Không có questionnaire/camera API; offlineLearningSupported=false | Không dựng API giả cho các màn chưa được xác nhận |

### 11.3. Trạng thái lỗi cần có trên UI

| API / tình huống | Phản hồi | UI nên xử lý |
|---|---|---|
| Premium collection với khách | 401 | Đưa về luồng đăng nhập khi người dùng muốn tiếp tục mở/mua |
| Premium collection với account chưa có quyền | 403 | Hiện paywall phù hợp |
| Practice chưa đủ lesson/nội dung | 409 với code tương ứng | Hiện cần học thêm hoặc thiếu nội dung, không báo lỗi mạng chung |
| Practice nguồn sai ID/version hoặc payload sai | 422 | Kiểm tra nguồn dữ liệu; không thay phiên bản âm thầm |
| Phiên bản nội dung bị thu hồi | 410 CONTENT_VERSION_RETIRED | Với bài dở, cho bắt đầu lại rõ ràng; với Practice, xử lý nguồn bị thu hồi, không thay bằng bản current tự động |
| Sync revision conflict | 409 | Pull/merge rồi gửi mutation mới |
| Sync cursor hết hạn | 410 | Đọc snapshot mới, bảo toàn pending local |
| Rate limit | 429 | Hiện thời gian chờ nếu có; tránh gửi lặp liên tục |
| Không tìm thấy resource | 404 | Hiện nội dung không còn khả dụng, cho quay lại |

Các nhánh cụ thể phải đọc `error.code`, không chỉ dựa vào status vì cùng 409/410 có thể xuất phát từ nghiệp vụ khác nhau. Retry/dedupe bám quy định từng endpoint.

## 12. Phụ lục tra cứu toàn bộ endpoint

Phụ lục dưới đây liệt kê toàn bộ operations của đúng file YAML được đối chiếu. Một operation có thể phục vụ nhiều màn; một màn cũng có thể hoàn toàn local.

| # | API / vị trí trong YAML | Màn hình hoặc tác vụ | Auth | Phase trong contract |
|---|---|---|---|---|
| 1 | [GET /api/v1/config](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:17>) | Splash/bootstrap; Help Center | Public | CORE |
| 2 | [GET /api/v1/home](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:108>) | Home và phân trang lộ trình | Public | CORE |
| 3 | [GET /api/v1/units](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:225>) | Repository catalog; thay thế danh sách unit trong Home | Public | CORE |
| 4 | [GET /api/v1/units/{unitId}](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:318>) | Tải riêng unit khi cần | Public | CORE |
| 5 | [GET /api/v1/lessons/{lessonId}](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:411>) | Lesson/ABC/Review/Conversation; resume | Public | CORE |
| 6 | [GET /api/v1/signs](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1002>) | Bộ chọn sign; danh sách dictionary | Public | CORE |
| 7 | [GET /api/v1/signs/{signId}](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1096>) | Sign detail; bổ sung dữ liệu favorites/collection riêng | Public | CORE |
| 8 | [GET /api/v1/collections](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1202>) | Signs → Public | Public | CORE |
| 9 | [GET /api/v1/collections/{collectionId}](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1307>) | Collection detail; mở shared link | Public | CORE |
| 10 | [GET /api/v1/collections/{collectionId}/signs](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1400>) | Danh sách sign của catalog collection | Conditional / token tùy chọn | CORE |
| 11 | [GET /api/v1/search](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1544>) | Search; bộ chọn sign | Public | CORE |
| 12 | [POST /api/v1/practice/availability](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1667>) | Tab Practice, trạng thái bốn mode | Public | CORE |
| 13 | [POST /api/v1/practice/sets](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:1884>) | Bắt đầu Quiz/Flashcards/Sign Matching/Alphabet Matching | Public | CORE |
| 14 | [POST /api/v1/media/urls](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2304>) | Player lesson/sign/Practice khi media URL hết hạn | Public | CORE |
| 15 | [POST /api/v1/reports](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2422>) | Report lesson/sign/collection/Quiz | Conditional / token tùy chọn | CORE |
| 16 | [GET /api/v1/legal-documents](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2581>) | Welcome; legal links trên Profile | Public | LATER_AUTH |
| 17 | [POST /api/v1/auth/otp/request](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2658>) | Email login/register | Public | LATER_AUTH |
| 18 | [POST /api/v1/auth/otp/challenges/{challengeId}/resend](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2748>) | Access Code → Resend | Public | LATER_AUTH |
| 19 | [POST /api/v1/auth/otp/verify](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2847>) | Access Code → xác nhận đăng nhập | Public | LATER_AUTH |
| 20 | [POST /api/v1/auth/refresh](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:2957>) | Quản lý phiên dùng chung | Public | LATER_AUTH |
| 21 | [POST /api/v1/auth/logout](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3064>) | Profile → Logout | Public | LATER_AUTH |
| 22 | [GET /api/v1/me](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3142>) | Profile tài khoản | Bearer | LATER_AUTH |
| 23 | [PATCH /api/v1/me](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3231>) | Profile → sửa tên | Bearer | LATER_AUTH |
| 24 | [GET /api/v1/me/stats](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3331>) | Đối chiếu số liệu Home/Profile sau sync | Bearer | LATER_SYNC |
| 25 | [GET /api/v1/sync/snapshot](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3424>) | Sau đăng nhập; khôi phục sync khi cursor hết hạn | Bearer | LATER_SYNC |
| 26 | [GET /api/v1/sync/changes](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3593>) | Đồng bộ thay đổi tài khoản | Bearer | LATER_SYNC |
| 27 | [POST /api/v1/sync/mutations](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3726>) | Attempt, favorites, collection riêng, preferences | Bearer | LATER_SYNC |
| 28 | [GET /api/v1/subscription/products](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:3991>) | Paywall → Plans | Public | LATER_PREMIUM |
| 29 | [GET /api/v1/me/billing-context](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4082>) | Chuẩn bị mua qua native Store | Bearer | LATER_PREMIUM |
| 30 | [GET /api/v1/me/entitlements](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4171>) | Quyền Premium tại Profile/Signs/paywall | Bearer | LATER_PREMIUM |
| 31 | [GET /api/v1/me/subscriptions](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4261>) | Profile → Subscription | Bearer | LATER_PREMIUM |
| 32 | [POST /api/v1/purchases/verify](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4367>) | Sau Store purchase | Bearer | LATER_PREMIUM |
| 33 | [GET /api/v1/purchases/{purchaseId}](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4528>) | Theo dõi giao dịch verify/restore pending | Bearer | LATER_PREMIUM |
| 34 | [POST /api/v1/purchases/restore](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4646>) | Profile → Restore purchase | Bearer | LATER_PREMIUM |
| 35 | [GET /api/v1/subscriptions/{subscriptionId}/management-link](<D:/VS_Code/problems.Java/ASL App/backend/openapi.mobile.yaml:4787>) | Profile → Manage/Cancel subscription | Bearer | LATER_PREMIUM |

## 13. Dấu vết phiên bản nguồn

SHA-256 tại thời điểm đối chiếu:

- OpenAPI: `3135d5d9128bdda0402a0a4a4922da1a91fe08d2c95d48341c68088fa327db24`.
- PDF: `df89c17247693a0d089697c53f4b708b858d855f4871608617f3fe2749331744`.

Tài liệu chỉ đối chiếu nội dung tĩnh, không chạy giao dịch mua, gửi report, thay đổi account hoặc kiểm thử backend thật. Endpoint appendix và request Practice mẫu được kiểm tra lại với file YAML khi tạo tài liệu.
