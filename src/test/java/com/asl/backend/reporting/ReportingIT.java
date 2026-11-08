package com.asl.backend.reporting;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.reporting.application.api.ReportingApi.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
/** Synthetic content only; verifies real constraints and concurrent durable receipt insertion. */
@ActiveProfiles("integration")
@SpringBootTest(properties={"spring.config.import=","asl.media.development-fixtures=false","asl.media.public-base-url=https://cdn.example.org"})
class ReportingIT {
    @Autowired ReportingApi api;
    @Autowired JdbcTemplate jdbc;
    UUID sign,asset;
    @BeforeEach void fixture() {
        sign=UUID.randomUUID(); asset=UUID.randomUUID();
        jdbc.update("insert into asl.media_assets(id,storage_key,mime_type,sha256_checksum,rights_reference,published_at) values (?,?,'video/mp4',?,'Synthetic reporting integration metadata',now())",asset,"reporting-fixture/"+asset,"0".repeat(64));
        jdbc.update("insert into asl.signs(id,name,content_kind,video_asset_id,published_at,retired_at) values (?,'Synthetic report sign','WORD',?,now(),now())",sign,asset);
    }
    @Test void concurrentGuestRetriesPersistOneReceiptEvenWithWithdrawnMedia() throws Exception {
        jdbc.update("update asl.media_assets set revoked_at=now() where id=?",asset);
        var c=new Command(UUID.randomUUID(),"SIGN",sign,null,"VIDEO_PROBLEM","Synthetic report fixture",null);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1); List<Future<Receipt>> results=new ArrayList<>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->{ start.await(); return api.submit(c,null); }));
            start.countDown(); var receipts=new HashSet<Receipt>(); for(var result:results) receipts.add(result.get(15,TimeUnit.SECONDS));
            assertThat(receipts).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from asl.user_reports where client_report_id=?",Integer.class,c.clientReportId())).isEqualTo(1);
        assertThatThrownBy(()->api.submit(new Command(c.clientReportId(),"SIGN",sign,null,"OTHER","changed",null),null)).isInstanceOfSatisfying(ApplicationFailure.class,f->assertThat(f.code()).isEqualTo("REPORT_ID_REUSED"));
    }
    @Test void revokedPracticeSourceAndExpiredMediaAreRetainedAsSnapshot() {
        var unit=UUID.randomUUID(); var lesson=UUID.randomUUID(); var version=UUID.randomUUID(); var target=UUID.randomUUID();
        jdbc.update("insert into asl.units(id,title,position) values (?,'Synthetic report unit',(select coalesce(max(position)+1,0) from asl.units))",unit);
        jdbc.update("insert into asl.lessons(id,unit_id,position,retired_at) values (?,?,0,now())",lesson,unit);
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,1,'Synthetic report source','LESSON')",version,lesson);
        jdbc.update("insert into asl.lesson_version_signs values (?,?,'Synthetic sign','WORD',?,0)",version,sign,asset);
        jdbc.update("insert into asl.lesson_exercises(lesson_version_id,position,exercise_type,payload,points_per_correct) values (?,0,'TIP','{}',0)",version);
        jdbc.update("update asl.lesson_versions set published_at=now(),revoked_at=now() where id=?",version);
        var media=new LinkedHashMap<String,Object>(); media.put("assetId",asset.toString()); media.put("url","https://cdn.example.org/expired.mp4"); media.put("mimeType","video/mp4"); media.put("expiresAt","2020-01-01T00:00:00Z"); media.put("durationMs",null);
        // A second historic source sign supplies a distinct option without requesting fresh media URLs.
        var other=UUID.randomUUID(); jdbc.update("insert into asl.signs(id,name,content_kind,video_asset_id) values (?,'Other synthetic sign','WORD',?)",other,asset);
        // Published child rows are immutable, so create a second published source version.
        var version2=UUID.randomUUID(); jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,2,'Synthetic report source 2','LESSON')",version2,lesson);
        jdbc.update("insert into asl.lesson_version_signs values (?,?,'Other synthetic sign','WORD',?,0)",version2,other,asset);
        jdbc.update("insert into asl.lesson_exercises(lesson_version_id,position,exercise_type,payload,points_per_correct) values (?,0,'TIP','{}',0)",version2);
        jdbc.update("update asl.lesson_versions set published_at=now(),revoked_at=now() where id=?",version2);
        jdbc.update("update asl.media_assets set revoked_at=now() where id=?",asset);
        var options=List.of(Map.of("id","a","signId",sign.toString(),"label","First"),Map.of("id","b","signId",other.toString(),"label","Other"));
        var item=Map.<String,Object>of("id",target.toString(),"signId",sign.toString(),"type","VIDEO_TO_WORD","payload",Map.of("video",media,"layout","LIST","options",options),"answerKey",Map.of("correctOptionId","b"));
        var context=Map.<String,Object>of("setId",UUID.randomUUID().toString(),"mode","QUIZ","generatedAt","2026-11-07T00:00:00Z","sourceLessons",List.of(Map.of("lessonId",lesson.toString(),"versionId",version.toString()),Map.of("lessonId",lesson.toString(),"versionId",version2.toString())),"item",item);
        var c=new Command(UUID.randomUUID(),"PRACTICE_ITEM",target,null,"WRONG_ANSWER","Synthetic wrong answer report",context);
        var receipt=api.submit(c,null); assertThat(api.submit(c,null)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("select practice_context->'item'->'payload'->'video'->>'url' from asl.user_reports where id=?",String.class,receipt.id())).isEqualTo(media.get("url"));
    }
}
