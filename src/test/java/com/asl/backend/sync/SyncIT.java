package com.asl.backend.sync;

import com.asl.backend.sync.application.api.*;
import com.asl.backend.learning.application.api.LearningApi;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic learning metadata only. Runs exclusively against TEST_DB_URL. */
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties={"spring.config.import=","asl.sync.cursor-secret=integration-only-secret-at-least-32-bytes"})
class SyncIT {
    @Autowired SyncApi api;
    @Autowired LearningApi learning;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    final UUID unit=UUID.randomUUID(),lesson=UUID.randomUUID(),version=UUID.randomUUID(),matching=UUID.randomUUID();
    final List<UUID> questions=new ArrayList<>();
    UUID owner;
    void capture(String operation,Map<String,Object> result) {
        try {
            var folder=java.nio.file.Path.of("target/sync-http");java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve(operation+"-200-postgres.json"),json.writeValueAsString(result));
        } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @BeforeAll void content() {
        jdbc.update("insert into asl.units(id,title,position,published_at) values (?,'Synthetic sync integration',(select coalesce(max(position)+1,0) from asl.units),now())",unit);
        jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,0)",lesson,unit);
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,1,'Synthetic 100 point fixture','LESSON')",version,lesson);
        for(int i=0;i<8;i++) {
            UUID q=UUID.randomUUID(); questions.add(q);
            var payload=Map.of("schemaVersion",1,"question","Synthetic question","options",List.of(Map.of("id","yes","text","Yes"),Map.of("id","no","text","No")));
            jdbc.update("insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,payload,answer_key,points_per_correct) values (?,?,?,'KNOWLEDGE',?::jsonb,'{\"correctOptionId\":\"yes\"}'::jsonb,10)",q,version,i,json.writeValueAsString(payload));
        }
        var payload=Map.of("schemaVersion",1,"left",List.of(Map.of("id","a","text","A"),Map.of("id","b","text","B")),"right",List.of(Map.of("id","x","text","X"),Map.of("id","y","text","Y")));
        jdbc.update("insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,payload,answer_key,points_per_correct) values (?,?,8,'MATCH_PAIRS',?::jsonb,'{\"pairs\":{\"a\":\"x\",\"b\":\"y\"}}'::jsonb,10)",matching,version,json.writeValueAsString(payload));
        learning.publish(version);
    }
    @BeforeEach void account() { owner=UUID.randomUUID(); jdbc.update("insert into asl.app_users(id,firebase_uid,time_zone) values (?,?,'UTC')",owner,"sync-it-"+owner); }
    Map<String,Object> mutation(String kind,String key,long base,Map<String,Object> data) { return Map.of("kind",kind,"entityKey",key,"baseRevision",base,"mutationId",UUID.randomUUID().toString(),"data",data); }
    Map<String,Object> collection(UUID id,boolean deleted) { return Map.of("id",id.toString(),"name","Synthetic collection","signIds",List.of(),"deleted",deleted); }
    Map<String,Object> preferences(String zone) { var p=new LinkedHashMap<String,Object>(); p.put("hapticsEnabled",true); p.put("dailyReminderEnabled",false); p.put("dailyReminderLocalTime",null); p.put("learningGoalDays","7"); p.put("timeZone",zone); return p; }
    Map<String,Object> push(Map<String,Object>... ms) { return api.push(owner,Map.of("mutations",List.of(ms))); }
    @Test void retryIsExactlyOnceAndReplayAddsPointsWithoutIncreasingDistinctLessons() {
        UUID attempt=UUID.randomUUID(); var m=mutation("LESSON_ATTEMPT",attempt.toString(),0,attempt(attempt,true));
        var applied=push(m); capture("sync_push",applied);
        assertThat(applied.toString()).contains("APPLIED"); assertThat(push(m).toString()).contains("DUPLICATE");
        assertThat(((Number)api.stats(owner).get("totalPoints")).longValue()).isEqualTo(90);
        var snapshot=api.snapshot(owner,null,20); capture("sync_snapshot",snapshot);
        UUID replay=UUID.randomUUID(); push(mutation("LESSON_ATTEMPT",replay.toString(),0,attempt(replay,true)));
        capture("sync_changes",api.changes(owner,(String)snapshot.get("syncCursor"),null,20));
        var stats=api.stats(owner); capture("account_stats",stats); assertThat(((Number)stats.get("totalPoints")).longValue()).isEqualTo(180);
        assertThat((List<?>)stats.get("completedLessonIds")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from asl.completion_ledger where user_id=?",Integer.class,owner)).isEqualTo(2);
    }
    @Test void batchRollsBackEarlierMutationIfLaterReferenceIsInvalid() {
        UUID id=UUID.randomUUID(); var first=mutation("PERSONAL_COLLECTION",id.toString(),0,collection(id,false));
        UUID missing=UUID.randomUUID(); var invalid=mutation("FAVORITE","SIGN:"+missing,0,Map.of("targetType","SIGN","targetId",missing.toString(),"isFavorite",true));
        assertThatThrownBy(()->push(first,invalid)).isInstanceOf(SyncFailure.class);
        assertThat(jdbc.queryForObject("select count(*) from asl.personal_collections where user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asl.sync_mutation_receipts where user_id=?",Integer.class,owner)).isZero();
        assertThat(push(first).toString()).contains("APPLIED");
    }
    @Test void staleRevisionRollsBackWholeBatchAndChangedIdBodyIsRejected() {
        var original=mutation("PREFERENCES","preferences",0,preferences("UTC")); push(original);
        UUID id=UUID.randomUUID(); var first=mutation("PERSONAL_COLLECTION",id.toString(),0,collection(id,false));
        assertThatThrownBy(()->push(first,mutation("PREFERENCES","preferences",0,preferences("Asia/Ho_Chi_Minh")))).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.currentRevision()).isEqualTo(1));
        assertThat(jdbc.queryForObject("select count(*) from asl.personal_collections where user_id=?",Integer.class,owner)).isZero();
        var changed=new HashMap<>(original); changed.put("data",preferences("Asia/Ho_Chi_Minh"));
        assertThatThrownBy(()->push(changed)).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("MUTATION_ID_REUSED"));
    }
    @Test void concurrentWritersSerializeAndOnlyOneWinsInitialRevision() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1); List<Future<String>> outcomes=new ArrayList<>();
            for(int i=0;i<2;i++) outcomes.add(pool.submit(()->{gate.await();try { push(mutation("PREFERENCES","preferences",0,preferences("UTC")));return "APPLIED"; }catch(SyncFailure e){return e.code();}}));
            gate.countDown(); assertThat(List.of(outcomes.get(0).get(20,TimeUnit.SECONDS),outcomes.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("APPLIED","ENTITY_REVISION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from asl.sync_changes where user_id=?",Integer.class,owner)).isEqualTo(1);
    }
    @Test void snapshotIsPinnedAcrossPagesAndTombstonesSurviveFreshSnapshot() {
        UUID id=UUID.randomUUID(); push(mutation("PREFERENCES","preferences",0,preferences("UTC")),mutation("PERSONAL_COLLECTION",id.toString(),0,collection(id,false)));
        var first=api.snapshot(owner,null,1); assertThat(first.get("syncCursor")).isNull();
        push(mutation("PERSONAL_COLLECTION",id.toString(),1,collection(id,true)));
        var last=api.snapshot(owner,(String)first.get("nextCursor"),1);
        assertThat(last.toString()).contains("deleted=false"); assertThat(last.get("nextCursor")).isNull();
        var delta=api.changes(owner,(String)last.get("syncCursor"),null,20); assertThat(delta.toString()).contains("deleted=true");
        assertThat(api.snapshot(owner,null,20).toString()).contains("deleted=true");
        assertThatThrownBy(()->push(mutation("PERSONAL_COLLECTION",id.toString(),2,collection(id,false)))).isInstanceOf(SyncFailure.class);
        UUID other=UUID.randomUUID(); assertThatThrownBy(()->api.snapshot(other,(String)first.get("nextCursor"),1)).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("NOT_FOUND"));
    }
    @Test void removedFavoritesRemainInSnapshotWithoutGrantingPremiumAccess() {
        UUID target=UUID.randomUUID();jdbc.update("insert into asl.collections(id,name,is_premium,published_at) values (?,'Synthetic premium favorite',true,now())",target);
        var data=new HashMap<String,Object>(Map.of("targetType","COLLECTION","targetId",target.toString(),"isFavorite",true));
        push(mutation("FAVORITE","COLLECTION:"+target,0,data));data.put("isFavorite",false);push(mutation("FAVORITE","COLLECTION:"+target,1,data));
        assertThat(api.snapshot(owner,null,20).toString()).contains("isFavorite=false");
        assertThat(jdbc.queryForObject("select count(*) from asl.user_entitlements where user_id=?",Integer.class,owner)).isZero();
    }
    @Test void firstAnswersCannotBeOverwrittenDuringReviewAndTerminalAttemptsAreImmutable() {
        UUID id=UUID.randomUUID(); var inProgress=attempt(id,false); push(mutation("LESSON_ATTEMPT",id.toString(),0,inProgress));
        var changed=attempt(id,false); var checkpoint=new HashMap<>(obj(changed.get("checkpoint"))); var answers=new ArrayList<>(list(checkpoint.get("firstAnswers")));
        answers.set(0,Map.of("exerciseId",questions.getFirst().toString(),"answer",Map.of("type","CHOICE","optionId","yes"),"isCorrect",true)); checkpoint.put("firstAnswers",answers); checkpoint.put("temporaryPoints",100); changed.put("checkpoint",checkpoint);
        assertThatThrownBy(()->push(mutation("LESSON_ATTEMPT",id.toString(),1,changed))).isInstanceOf(SyncFailure.class);
        var completed=attempt(id,true); push(mutation("LESSON_ATTEMPT",id.toString(),1,completed));
        assertThat(push(mutation("LESSON_ATTEMPT",id.toString(),2,completed)).toString()).contains("DUPLICATE");
        var edited=new HashMap<>(completed);edited.put("completionTimeZone","Asia/Ho_Chi_Minh");
        assertThatThrownBy(()->push(mutation("LESSON_ATTEMPT",id.toString(),2,edited))).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("ATTEMPT_IMMUTABLE"));
    }
    @Test void invalidScoreOrDuplicateMatchingPairsNeverAwardsPoints() {
        UUID id=UUID.randomUUID(); var bad=attempt(id,true); bad.put("scorePercent",100);
        assertThatThrownBy(()->push(mutation("LESSON_ATTEMPT",id.toString(),0,bad))).isInstanceOf(SyncFailure.class);
        var cp=new HashMap<>(obj(bad.get("checkpoint"))); cp.put("matchingProgress",List.of(Map.of("exerciseId",matching.toString(),"matchedPairs",List.of(Map.of("leftId","a","rightId","x"),Map.of("leftId","a","rightId","x"))))); bad.put("checkpoint",cp); bad.put("scorePercent",90);
        assertThatThrownBy(()->push(mutation("LESSON_ATTEMPT",id.toString(),0,bad))).isInstanceOf(SyncFailure.class);
        assertThat(((Number)api.stats(owner).get("totalPoints")).longValue()).isZero();
    }
    @Test void completedHistorySurvivesVersionRevocationWhilePendingBatchRollsBack() {
        // A dedicated version keeps other tests independent.
        UUID l=UUID.randomUUID(),v=UUID.randomUUID(),q=UUID.randomUUID();
        jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,(select max(position)+1 from asl.lessons where unit_id=?))",l,unit,unit);
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,1,'Revoked synthetic fixture','LESSON')",v,l);
        jdbc.update("insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,payload,answer_key,points_per_correct) select ?,?,0,exercise_type,payload,answer_key,points_per_correct from asl.lesson_exercises where id=?",q,v,questions.getFirst()); learning.publish(v); learning.revoke(v);
        UUID id=UUID.randomUUID(); var pending=attempt(id,false); pending.put("lessonId",l.toString());pending.put("versionId",v.toString());
        UUID collection=UUID.randomUUID(); assertThatThrownBy(()->push(mutation("PERSONAL_COLLECTION",collection.toString(),0,collection(collection,false)),mutation("LESSON_ATTEMPT",id.toString(),0,pending))).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("CONTENT_VERSION_RETIRED"));
        assertThat(jdbc.queryForObject("select count(*) from asl.personal_collections where user_id=?",Integer.class,owner)).isZero();
    }
    @Test void zeroPointConversationCountsForStreakAndTimezoneChangeKeepsHistoricalDate() {
        UUID l=UUID.randomUUID(),v=UUID.randomUUID(),turn=UUID.randomUUID(),asset=UUID.randomUUID();
        jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,(select max(position)+1 from asl.lessons where unit_id=?))",l,unit,unit);
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,1,'Synthetic conversation','CONVERSATION')",v,l);
        jdbc.update("insert into asl.media_assets(id,storage_key,mime_type,sha256_checksum,rights_reference,published_at) values (?,?,'video/mp4',?,'synthetic metadata only',now())",asset,"sync-it/"+asset,"0".repeat(64));
        jdbc.update("insert into asl.conversation_turns(id,lesson_version_id,position,speaker_side,gloss_caption,english_translation,video_asset_id) values (?,?,0,'PARTNER','HELLO','Hello',?)",turn,v,asset); learning.publish(v);
        UUID id=UUID.randomUUID(); var d=attempt(id,true); d.put("lessonId",l.toString()); d.put("versionId",v.toString());d.put("earnedPoints",0);d.put("scorePercent",null);
        String completion=LocalDate.now(ZoneOffset.UTC).minusDays(1).atTime(12,0).toInstant(ZoneOffset.UTC).toString();d.put("completedAt",completion);d.put("updatedAt",completion);
        var cp=new HashMap<>(obj(d.get("checkpoint")));cp.put("firstAnswers",List.of());cp.put("reviewResolvedExerciseIds",List.of());cp.put("matchingProgress",List.of());cp.put("temporaryPoints",0);cp.put("completedStepIds",List.of(turn.toString()));d.put("checkpoint",cp);
        push(mutation("LESSON_ATTEMPT",id.toString(),0,d));
        var stats=api.stats(owner);assertThat(((Number)stats.get("totalPoints")).longValue()).isZero();assertThat(((Number)stats.get("currentStreak")).intValue()).isEqualTo(1);assertThat(((Number)stats.get("recordStreak")).intValue()).isEqualTo(1);
        var dates=stats.get("activityDates");push(mutation("PREFERENCES","preferences",0,preferences("America/Los_Angeles")));assertThat(api.stats(owner).get("activityDates")).isEqualTo(dates);
    }
    Map<String,Object> attempt(UUID id,boolean complete) {
        var cp=new LinkedHashMap<String,Object>(); cp.put("phase",complete?"RESULT":"REVIEW"); cp.put("currentStepId",complete?null:questions.getFirst().toString());
        var answers=new ArrayList<Map<String,Object>>(); for(int i=0;i<8;i++) answers.add(Map.of("exerciseId",questions.get(i).toString(),"answer",Map.of("type","CHOICE","optionId",i==0?"no":"yes"),"isCorrect",i!=0));
        cp.put("firstAnswers",answers); cp.put("reviewQueue",complete?List.of():List.of(questions.getFirst().toString())); cp.put("reviewResolvedExerciseIds",complete?List.of(questions.getFirst().toString()):List.of());
        cp.put("matchingProgress",List.of(Map.of("exerciseId",matching.toString(),"matchedPairs",List.of(Map.of("leftId","a","rightId","x"),Map.of("leftId","b","rightId","y")))));
        cp.put("draftAnswer",null); cp.put("slowPlayback",false);cp.put("temporaryPoints",90);
        var steps=new ArrayList<String>(questions.stream().map(UUID::toString).toList());steps.add(matching.toString());cp.put("completedStepIds",steps);
        Instant time=Instant.now().minusSeconds(60); var d=new LinkedHashMap<String,Object>();d.put("attemptId",id.toString());d.put("lessonId",lesson.toString());d.put("versionId",version.toString());d.put("status",complete?"COMPLETED":"IN_PROGRESS");
        // Stable start permits the same device attempt to be resumed in a later request.
        String start=LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        d.put("startedAt",start);d.put("updatedAt",time.toString());d.put("completedAt",complete?time.toString():null);d.put("completionTimeZone","UTC");d.put("checkpoint",cp);d.put("earnedPoints",complete?90:0);d.put("scorePercent",complete?90:null);return d;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Object> list(Object value){return (List<Object>)value;}
}
