package com.asl.backend.learning.adapter.out.persistence;
import com.asl.backend.learning.application.api.ReportContentReader;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class JdbcReportContentReader implements ReportContentReader {
    private final JdbcTemplate jdbc;
    public JdbcReportContentReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean lessonExists(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asl.lessons where id=?)",Boolean.class,id)); }
    public boolean exerciseExists(UUID id,UUID version) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asl.lesson_exercises where id=? and lesson_version_id=?)",Boolean.class,id,version)); }
    public Map<Source,Set<UUID>> sourceSigns(List<Source> sources) {
        Map<Source,Set<UUID>> result=new HashMap<>(); if(sources.isEmpty()) return result;
        // UUIDs are parsed before this boundary; SQL array text uses bound parameters, never interpolation.
        String lessons="{"+String.join(",",sources.stream().map(s->s.lessonId().toString()).toList())+"}";
        String versions="{"+String.join(",",sources.stream().map(s->s.versionId().toString()).toList())+"}";
        jdbc.query("select v.lesson_id,v.id,s.sign_id from unnest(cast(? as uuid[]),cast(? as uuid[])) wanted(lesson_id,version_id) join asl.lesson_versions v on v.lesson_id=wanted.lesson_id and v.id=wanted.version_id left join asl.lesson_version_signs s on s.lesson_version_id=v.id where v.published_at is not null",r->{
            var source=new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class));
            var signs=result.computeIfAbsent(source,ignored->new HashSet<>()); var sign=r.getObject(3,UUID.class); if(sign!=null) signs.add(sign);
        },lessons,versions);
        return result;
    }
}
