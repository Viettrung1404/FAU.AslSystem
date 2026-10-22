package com.asl.backend.catalog.adapter.out.persistence;
import com.asl.backend.catalog.application.port.out.CatalogRepository;
import com.asl.backend.catalog.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    private final NamedParameterJdbcTemplate sql;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcCatalogRepository(JdbcTemplate jdbc) { sql = new NamedParameterJdbcTemplate(jdbc); }

    @Override public List<UnitReference> findUnits(UnitPosition after, int count, Instant at) {
        var parameters = new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("count", count);
        String seek = "";
        if (after != null) {
            seek = " and (position > :position or (position = :position and id > :id))";
            parameters.addValue("position", after.position()).addValue("id", after.id());
        }
        return sql.query("select id,title,position from asl.units where published_at<=:at and retired_at is null"
                + seek + " order by position,id limit :count", parameters,
                (row, index) -> new UnitReference(row.getObject("id", UUID.class), row.getString("title"), row.getInt("position")));
    }
    @Override public Optional<UnitReference> findUnit(UUID id, Instant at) {
        return sql.query("select id,title,position from asl.units where id=:id and published_at<=:at and retired_at is null",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Timestamp.from(at)),
                (row, index) -> new UnitReference(row.getObject("id", UUID.class), row.getString("title"), row.getInt("position")))
                .stream().findFirst();
    }
    @Override public List<LessonReference> findLessons(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, LessonBuilder> results = new LinkedHashMap<>();
        sql.query("""
                select l.id,l.unit_id,l.position,p.prerequisite_lesson_id
                from asl.lessons l left join asl.lesson_prerequisites p on p.lesson_id=l.id
                where l.unit_id in (:ids) and l.retired_at is null
                order by l.unit_id,l.position,l.id,p.prerequisite_lesson_id
                """, Map.of("ids", ids), (RowCallbackHandler) row -> {
            UUID id = row.getObject("id", UUID.class);
            var lesson = results.get(id);
            if (lesson == null) {
                lesson = new LessonBuilder(row.getObject("unit_id", UUID.class), id, row.getInt("position"));
                results.put(id, lesson);
            }
            UUID prerequisite = row.getObject("prerequisite_lesson_id", UUID.class);
            if (prerequisite != null) lesson.prerequisites.add(prerequisite);
        });
        return results.values().stream().map(lesson -> new LessonReference(lesson.unit, lesson.id, lesson.position, lesson.prerequisites)).toList();
    }
    @Override public CatalogPolicy policy() {
        Map<String, String> rows = new HashMap<>();
        sql.getJdbcTemplate().query("select setting_key,setting_value::text from asl.app_settings where setting_key in ('mobile_policy','scoring_policy')",
                (RowCallbackHandler) row -> rows.put(row.getString(1), row.getString(2)));
        try {
            Map<?, ?> mobile = json.readValue(rows.get("mobile_policy"), Map.class);
            Map<?, ?> scoring = json.readValue(rows.get("scoring_policy"), Map.class);
            if (!mobile.keySet().equals(Set.of("apiVersion", "contractVersion", "dailyLessonLimit", "practiceMinimumDistinctLessons", "practiceModes", "offlineLearningSupported"))
                    || !scoring.keySet().equals(Set.of("version", "ordinaryQuestions", "matching", "reviewPoints", "commitOn", "replayEarnsPoints"))) throw new IllegalArgumentException();
            return new CatalogPolicy((String) mobile.get("apiVersion"), (String) mobile.get("contractVersion"), (Integer) mobile.get("dailyLessonLimit"),
                    (Integer) mobile.get("practiceMinimumDistinctLessons"), ((List<?>) mobile.get("practiceModes")).stream().map(String.class::cast).toList(),
                    (Boolean) mobile.get("offlineLearningSupported"), new CatalogPolicy.Scoring((Integer) scoring.get("version"),
                    (String) scoring.get("ordinaryQuestions"), (String) scoring.get("matching"), (Integer) scoring.get("reviewPoints"),
                    (String) scoring.get("commitOn"), (Boolean) scoring.get("replayEarnsPoints")));
        } catch (RuntimeException invalidPolicy) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Catalog policy unavailable", invalidPolicy);
        }
    }
    private static final class LessonBuilder {
        final UUID unit; final UUID id; final int position; final List<UUID> prerequisites = new ArrayList<>();
        LessonBuilder(UUID unit, UUID id, int position) { this.unit = unit; this.id = id; this.position = position; }
    }
}
