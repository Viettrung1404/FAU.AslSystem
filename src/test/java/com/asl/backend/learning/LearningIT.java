package com.asl.backend.learning;

import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.config.import=", "asl.media.development-fixtures=false", "asl.media.public-base-url=https://cdn.example.org"})
class LearningIT {
    @Autowired LearningApi api;
    @Autowired LessonSummaryReader summaries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    JsonMapper json = JsonMapper.builder().build();
    @BeforeAll void fixtures() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            UUID unit = UUID.randomUUID();
            jdbc.update("insert into asl.units(id,title,position,published_at) values (?,'Learning integration fixture',(select coalesce(max(position)+1,0) from asl.units),now())", unit);
            for (int i=0; i<4; i++) {
                var v = LearningFixtures.version(i);
                var assets = new HashSet<UUID>(); v.signs().forEach(s -> assets.add(s.assetId())); v.exercises().forEach(e -> assets.addAll(e.media().values())); v.turns().forEach(t -> assets.add(t.assetId()));
                for (var asset : assets) jdbc.update("insert into asl.media_assets(id,storage_key,mime_type,sha256_checksum,rights_reference,published_at) values (?,?,'video/mp4',?,'synthetic integration metadata only',now()) on conflict(id) do nothing", asset, "integration/"+asset, "0".repeat(64));
                jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,?)", v.lessonId(), unit, i);
                jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,1,?,?)", v.versionId(), v.lessonId(), v.title(), v.type());
                for (var s : v.signs()) {
                    jdbc.update("insert into asl.signs(id,name,content_kind,video_asset_id,published_at) values (?,?,'WORD',?,now()) on conflict(id) do nothing", s.id(), s.name(), s.assetId());
                    jdbc.update("insert into asl.lesson_version_signs values (?,?,?,'WORD',?,?)", v.versionId(), s.id(), s.name(), s.assetId(), v.signs().indexOf(s));
                }
                for (var e : v.exercises()) {
                    jdbc.update("insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,prompt,payload,answer_key,points_per_correct) values (?,?,?,?,?,?::jsonb,?::jsonb,?)", e.id(), v.versionId(), e.position(), e.type(), e.prompt(), json.writeValueAsString(e.payload()), e.answerKey()==null ? null : json.writeValueAsString(e.answerKey()), e.points());
                    for (var sid : e.signIds()) jdbc.update("insert into asl.exercise_signs values (?,?,?)", v.versionId(), e.id(), sid);
                    e.media().forEach((slot,asset) -> jdbc.update("insert into asl.lesson_exercise_media values (?,?,?,?)", v.versionId(), e.id(), slot, asset));
                }
                for (var t : v.turns()) jdbc.update("insert into asl.conversation_turns values (?,?,?,?,?,?,?)", t.id(), v.versionId(), t.position(), t.side(), t.gloss(), t.translation(), t.assetId());
                api.publish(v.versionId());
            }
        });
    }
    @Test void realHttpDeliversAllTypesAndConversationAndMatchesSummary() throws Exception {
        for (int i=0; i<4; i++) {
            var v = LearningFixtures.version(i); var response = get(v.lessonId().toString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var body = json.readTree(response.body());
            assertThat(body.get("exercises").size()).isEqualTo(v.exercises().size());
            assertThat(body.get("conversationTurns").size()).isEqualTo(v.turns().size());
            assertThat(body.get("versionId").asString()).isEqualTo(summaries.current(List.of(v.lessonId()), Instant.now()).get(v.lessonId()).versionId().toString());
            capture("lesson_content-200-"+v.type(), response);
        }
    }
    @Test void oldVersionRemainsImmutableAfterPublishingNewVersionAndMediaRefresh() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var v = LearningFixtures.version(0); UUID next = UUID.randomUUID();
            var before = api.content(v.lessonId(), v.versionId());
            jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,2,'New content','LESSON')", next, v.lessonId());
            jdbc.update("insert into asl.lesson_exercises(lesson_version_id,position,exercise_type,prompt,payload,answer_key,points_per_correct) select ?,0,exercise_type,prompt,payload,answer_key,points_per_correct from asl.lesson_exercises where lesson_version_id=? and exercise_type='KNOWLEDGE'", next, v.versionId());
            api.publish(next);
            assertThat(api.content(v.lessonId(), null).get("versionId")).isEqualTo(next);
            assertThat(api.content(v.lessonId(), v.versionId())).isEqualTo(before);
            status.setRollbackOnly();
        });
    }
    @Test void unknownVersionsAndMalformedIdsUseContractErrors() throws Exception {
        var v = LearningFixtures.version(0);
        var unknown = get(v.lessonId()+"?versionId="+UUID.randomUUID()); assertThat(unknown.statusCode()).isEqualTo(404); capture("lesson_content-404", unknown);
        var wrongOwner = get(v.lessonId()+"?versionId="+LearningFixtures.version(3).versionId()); assertThat(wrongOwner.statusCode()).isEqualTo(404);
        var bad = get("invalid"); assertThat(bad.statusCode()).isEqualTo(400); capture("lesson_content-400", bad);
    }
    @Test void revocationIsPermanentAndDoesNotFallbackToOldVersion() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var v = LearningFixtures.version(1); api.revoke(v.versionId()); api.revoke(v.versionId());
            LearningServiceTest.code("CONTENT_VERSION_RETIRED", () -> api.content(v.lessonId(), v.versionId()));
            LearningServiceTest.code("NOT_FOUND", () -> api.content(v.lessonId(), null));
            LearningServiceTest.code("CONTENT_VERSION_RETIRED", () -> api.publish(v.versionId()));
            status.setRollbackOnly();
        });
    }
    @Test void publishedChildrenAreProtectedByDatabase() {
        var v = LearningFixtures.version(0);
        assertThatThrownBy(() -> jdbc.update("update asl.lesson_exercises set prompt='tampered' where lesson_version_id=?", v.versionId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private HttpResponse<String> get(String suffix) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/lessons/"+suffix)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void capture(String name, HttpResponse<String> response) throws Exception {
        Path folder = Path.of("target/learning-http"); Files.createDirectories(folder);
        Files.writeString(folder.resolve(name+".json"), response.body());
    }
}
