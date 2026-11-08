package com.asl.backend.sync.adapter.out.persistence;

import com.asl.backend.sync.application.port.out.SyncRepository;
import com.asl.backend.sync.application.api.SyncFailure;
import com.asl.backend.sync.application.SyncValidation;
import com.asl.backend.platform.application.api.ApplicationFailure.Kind;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcSyncRepository implements SyncRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public JdbcSyncRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lock(UUID owner) {
        jdbc.update("insert into asl.sync_streams(user_id) values (?) on conflict do nothing",owner);
        jdbc.queryForObject("select last_sequence from asl.sync_streams where user_id=? for update",Long.class,owner);
    }
    public long watermark(UUID owner) { var values=jdbc.queryForList("select last_sequence from asl.sync_streams where user_id=?",Long.class,owner); return values.isEmpty()?0:values.getFirst(); }
    public Optional<Receipt> receipt(UUID owner,UUID id) {
        return jdbc.query("select c.*,r.mutation_body from asl.sync_mutation_receipts r join asl.sync_changes c on c.user_id=r.user_id and c.sequence_no=r.change_sequence where r.user_id=? and r.mutation_id=?",
            (r,n)->new Receipt(object(r.getString("mutation_body")),change(r)),owner,id).stream().findFirst();
    }
    public Optional<Change> current(UUID owner,String kind,String key) {
        return jdbc.query("select * from asl.sync_changes where user_id=? and kind=? and entity_key=? order by sequence_no desc limit 1",(r,n)->change(r),owner,kind,key).stream().findFirst();
    }
    public Change save(UUID owner,String kind,String key,long revision,Map<String,Object> d,String path) {
        try { return saveValidated(owner,kind,key,revision,d,path); }
        catch(DataIntegrityViolationException failure) {
            String detail=Objects.toString(failure.getMostSpecificCause().getMessage(),"");
            String field=detail.contains("Temporary/max points")?"/checkpoint/temporaryPoints":detail.contains("correctness mismatch")?"/checkpoint/firstAnswers":detail.contains("First answer")||detail.contains("answer option")||detail.contains("answer token")?"/checkpoint/firstAnswers":detail.contains("Matching")||detail.contains("matching")?"/checkpoint/matchingProgress":detail.contains("Review")?"/checkpoint/reviewQueue":detail.contains("time")?"/updatedAt":"";
            throw invalid("Mutation violates frozen-content scoring, checkpoint progression or catalog references",path+field);
        }
    }
    private Change saveValidated(UUID owner,String kind,String key,long revision,Map<String,Object> d,String path) {
        switch(kind) {
            case "FAVORITE" -> {
                boolean sign=d.get("targetType").equals("SIGN"); UUID target=id(d,"targetId");
                Integer count=jdbc.queryForObject("select count(*) from asl."+(sign?"signs":"collections")+" where id=?",Integer.class,target);
                if(count==0) throw invalid("Favorite target does not exist",path+"/targetId");
                if(revision==1) jdbc.update("insert into asl.favorites(user_id,target_type,target_id,sign_id,collection_id,is_favorite,revision) values (?,?,?,?,?,?,?)",owner,d.get("targetType"),target,sign?target:null,sign?null:target,d.get("isFavorite"),revision);
                else jdbc.update("update asl.favorites set is_favorite=?,revision=? where user_id=? and target_type=? and target_id=?",d.get("isFavorite"),revision,owner,d.get("targetType"),target);
            }
            case "PERSONAL_COLLECTION" -> {
                var prior=current(owner,kind,key);
                if(prior.isPresent()&&Boolean.TRUE.equals(prior.get().data().get("deleted"))) throw new SyncFailure(Kind.CONFLICT,"ENTITY_REVISION_CONFLICT","Deleted collection cannot be restored",path,prior.get().revision());
                // PostgreSQL array literal contains only validated UUIDs.
                String ids="{"+String.join(",",((List<?>)d.get("signIds")).stream().map(Object::toString).toList())+"}";
                if(revision==1) jdbc.update("insert into asl.personal_collections(user_id,id,name,sign_ids,deleted,revision) values (?,?,?,?::uuid[],?,?)",owner,id(d,"id"),d.get("name"),ids,d.get("deleted"),revision);
                else jdbc.update("update asl.personal_collections set name=?,sign_ids=?::uuid[],deleted=?,revision=? where user_id=? and id=?",d.get("name"),ids,d.get("deleted"),revision,owner,id(d,"id"));
            }
            case "PREFERENCES" -> {
                if(revision==1) jdbc.update("insert into asl.user_preferences(user_id,haptics_enabled,daily_reminder_enabled,daily_reminder_local_time,learning_goal_days,time_zone,revision) values (?,?,?,?,?,?,?)",owner,d.get("hapticsEnabled"),d.get("dailyReminderEnabled"),d.get("dailyReminderLocalTime"),Integer.parseInt((String)d.get("learningGoalDays")),d.get("timeZone"),revision);
                else jdbc.update("update asl.user_preferences set haptics_enabled=?,daily_reminder_enabled=?,daily_reminder_local_time=?,learning_goal_days=?,time_zone=?,revision=? where user_id=?",d.get("hapticsEnabled"),d.get("dailyReminderEnabled"),d.get("dailyReminderLocalTime"),Integer.parseInt((String)d.get("learningGoalDays")),d.get("timeZone"),revision,owner);
            }
            case "LESSON_ATTEMPT" -> attempt(owner,key,revision,d,path);
            default -> throw invalid("Unknown entity kind",path);
        }
        return jdbc.query("insert into asl.sync_changes(user_id,sequence_no,kind,entity_key,revision,data) values (?,0,?,?,?,?::jsonb) returning *",(r,n)->change(r),owner,kind,key,revision,json.writeValueAsString(d)).getFirst();
    }
    private void attempt(UUID owner,String key,long revision,Map<String,Object> d,String path) {
        var existing=current(owner,"LESSON_ATTEMPT",key);
        if(existing.isPresent()&&!"IN_PROGRESS".equals(existing.get().data().get("status"))) throw new SyncFailure(Kind.CONFLICT,"ATTEMPT_IMMUTABLE","Completed or abandoned attempt is immutable",path,existing.get().revision());
        var versions=jdbc.queryForList("select published_at,revoked_at from asl.lesson_versions where id=? and lesson_id=? for share",id(d,"versionId"),id(d,"lessonId"));
        if(versions.isEmpty()||versions.getFirst().get("published_at")==null) throw invalid("Unknown or unpublished lesson version",path+"/versionId");
        if(versions.getFirst().get("revoked_at")!=null) throw new SyncFailure(Kind.REMOVED,"CONTENT_VERSION_RETIRED","Lesson version has been retired",path+"/versionId",null);
        Integer maximum=jdbc.queryForObject("select coalesce(sum(case when exercise_type='MATCH_PAIRS' then (select count(*) from jsonb_object_keys(answer_key->'pairs'))*points_per_correct else points_per_correct end),0)::integer from asl.lesson_exercises where lesson_version_id=?",Integer.class,id(d,"versionId"));
        BigDecimal expected="COMPLETED".equals(d.get("status"))&&maximum>0 ? BigDecimal.valueOf(((Number)d.get("earnedPoints")).longValue()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(maximum),2,RoundingMode.HALF_UP) : null;
        if(!SyncValidation.equivalent(expected,d.get("scorePercent"))) throw invalid("Score does not match accepted earned points and frozen maximum",path+"/scorePercent");
        if(revision==1) jdbc.update("insert into asl.lesson_attempts(user_id,attempt_id,lesson_id,lesson_version_id,status,started_at,client_updated_at,completed_at,completion_time_zone,checkpoint,max_points,earned_points,revision) values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",owner,id(d,"attemptId"),id(d,"lessonId"),id(d,"versionId"),d.get("status"),timestamp(d.get("startedAt")),timestamp(d.get("updatedAt")),timestamp(d.get("completedAt")),d.get("completionTimeZone"),json.writeValueAsString(d.get("checkpoint")),maximum,d.get("earnedPoints"),revision);
        else jdbc.update("update asl.lesson_attempts set lesson_id=?,lesson_version_id=?,status=?,started_at=?,client_updated_at=?,completed_at=?,completion_time_zone=?,checkpoint=?::jsonb,max_points=?,earned_points=?,revision=?,received_at=now() where user_id=? and attempt_id=?",id(d,"lessonId"),id(d,"versionId"),d.get("status"),timestamp(d.get("startedAt")),timestamp(d.get("updatedAt")),timestamp(d.get("completedAt")),d.get("completionTimeZone"),json.writeValueAsString(d.get("checkpoint")),maximum,d.get("earnedPoints"),revision,owner,id(d,"attemptId"));
    }
    public void receipt(UUID owner,UUID id,Map<String,Object> body,Change change) { jdbc.update("insert into asl.sync_mutation_receipts(user_id,mutation_id,mutation_body,change_sequence) values (?,?,?::jsonb,?)",owner,id,json.writeValueAsString(body),change.sequence()); }
    public List<Change> snapshot(UUID owner,long watermark,long after,int limit) {
        return jdbc.query("select * from (select distinct on(kind,entity_key) * from asl.sync_changes where user_id=? and sequence_no<=? order by kind,entity_key,sequence_no desc) pinned where sequence_no>? order by sequence_no limit ?",(r,n)->change(r),owner,watermark,after,limit);
    }
    public List<Change> changes(UUID owner,long watermark,long after,int limit) { return jdbc.query("select * from asl.sync_changes where user_id=? and sequence_no>? and sequence_no<=? order by sequence_no limit ?",(r,n)->change(r),owner,after,watermark,limit); }
    public Map<String,Object> stats(UUID owner) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("totalPoints",jdbc.queryForObject("select total_points from asl.user_total_points where user_id=?",Long.class,owner));
        result.put("completedLessonIds",jdbc.queryForList("select lesson_id from asl.user_completed_lessons where user_id=? order by lesson_id",UUID.class,owner));
        result.put("learnedSignIds",jdbc.queryForList("select sign_id from asl.user_learned_signs where user_id=? order by sign_id",UUID.class,owner));
        var streak=jdbc.queryForMap("select current_streak,record_streak from asl.user_streaks where user_id=?",owner);
        result.put("currentStreak",streak.get("current_streak")); result.put("recordStreak",streak.get("record_streak"));
        result.put("activityDates",jdbc.query("select activity_date from asl.user_activity_dates where user_id=? order by activity_date",(r,n)->r.getDate(1).toLocalDate().toString(),owner)); return result;
    }
    private Change change(ResultSet r) throws SQLException { return new Change(r.getLong("sequence_no"),r.getString("kind"),r.getString("entity_key"),r.getLong("revision"),object(r.getString("data"))); }
    private Map<String,Object> object(String data) { return json.readValue(data,new TypeReference<Map<String,Object>>(){}); }
    private static UUID id(Map<String,Object> d,String key) { return UUID.fromString((String)d.get(key)); }
    private static Timestamp timestamp(Object value) { return value==null ? null : Timestamp.from(OffsetDateTime.parse(value.toString()).toInstant()); }
    private static SyncFailure invalid(String message,String path) { return new SyncFailure(Kind.INVALID_INPUT,"VALIDATION_FAILED",message,path,null); }
}
