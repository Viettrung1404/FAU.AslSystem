package com.asl.backend.learning.adapter.out.persistence;

import com.asl.backend.learning.application.LearningService;
import com.asl.backend.learning.application.port.out.LessonRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcLessonRepository implements LessonRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcLessonRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Version> find(UUID lessonId, UUID versionId, Instant at) {
        // Retained explicit versions remain addressable even after catalog retirement.
        return load("select * from asl.lesson_versions where lesson_id=? and " + (versionId == null ? "is_current=true" : "id=?"),
                versionId == null ? new Object[]{lessonId} : new Object[]{lessonId, versionId});
    }
    @Override public Version draftForUpdate(UUID versionId) {
        return load("select * from asl.lesson_versions where id=? for update", versionId).orElseThrow(LearningService::missing);
    }
    private Optional<Version> load(String sql, Object... args) {
        var versions = jdbc.query(sql, (r, n) -> new Version(r.getObject("lesson_id", UUID.class), r.getObject("id", UUID.class), r.getString("title"),
                r.getString("lesson_type"), r.getTimestamp("published_at") == null ? null : r.getTimestamp("published_at").toInstant(),
                r.getTimestamp("revoked_at") == null ? null : r.getTimestamp("revoked_at").toInstant(), List.of(), List.of(), List.of()), args);
        if (versions.isEmpty()) return Optional.empty();
        var v = versions.getFirst(); var id = v.versionId();
        var signs = jdbc.query("select * from asl.lesson_version_signs where lesson_version_id=? order by position", (r, n) ->
                new Sign(r.getObject("sign_id", UUID.class), r.getString("name_snapshot"), r.getObject("video_asset_id", UUID.class)), id);
        // Bulk load associations; no query per exercise or media slot.
        Map<UUID, List<UUID>> signIds = new HashMap<>();
        jdbc.query("select exercise_id,sign_id from asl.exercise_signs where lesson_version_id=? order by exercise_id,sign_id", r -> {
            signIds.computeIfAbsent(r.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(r.getObject(2, UUID.class));
        }, id);
        Map<UUID, Map<String, UUID>> slots = new HashMap<>();
        jdbc.query("select exercise_id,slot_key,media_asset_id from asl.lesson_exercise_media where lesson_version_id=? order by exercise_id,slot_key", r -> {
            slots.computeIfAbsent(r.getObject(1, UUID.class), ignored -> new LinkedHashMap<>()).put(r.getString(2), r.getObject(3, UUID.class));
        }, id);
        var exercises = jdbc.query("select * from asl.lesson_exercises where lesson_version_id=? order by position", (r, n) -> {
            var eid = r.getObject("id", UUID.class);
            return new Exercise(eid, r.getInt("position"), r.getString("exercise_type"), r.getString("prompt"), object(r.getString("payload")),
                    object(r.getString("answer_key")), r.getInt("points_per_correct"), signIds.getOrDefault(eid, List.of()), slots.getOrDefault(eid, Map.of()));
        }, id);
        var turns = jdbc.query("select * from asl.conversation_turns where lesson_version_id=? order by position", (r, n) -> new Turn(
                r.getObject("id", UUID.class), r.getInt("position"), r.getString("speaker_side"), r.getString("gloss_caption"), r.getString("english_translation"),
                r.getObject("video_asset_id", UUID.class)), id);
        return Optional.of(new Version(v.lessonId(), id, v.title(), v.type(), v.publishedAt(), v.revokedAt(), signs, exercises, turns));
    }
    private Map<String, Object> object(String value) { return value == null ? null : json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
    @Override public void publish(UUID id, Instant at) {
        // Serialize publishers of different versions of the same logical lesson.
        jdbc.queryForObject("select id from asl.lessons where id=(select lesson_id from asl.lesson_versions where id=?) for update", UUID.class, id);
        jdbc.update("update asl.lesson_versions set is_current=false where lesson_id=(select lesson_id from asl.lesson_versions where id=?) and is_current", id);
        jdbc.update("update asl.lesson_versions set published_at=?,is_current=true where id=? and published_at is null", java.sql.Timestamp.from(at), id);
    }
    @Override public void revoke(UUID id, Instant at) {
        var v = draftForUpdate(id);
        if (v.publishedAt() == null) throw LearningService.missing();
        jdbc.update("update asl.lesson_versions set is_current=false,revoked_at=coalesce(revoked_at,?) where id=?", java.sql.Timestamp.from(at), id);
    }
}
