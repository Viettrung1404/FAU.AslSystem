package com.asl.backend.learning.adapter.out.persistence;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import jakarta.persistence.*;
@Repository
public class JpaLessonSummaryReader implements LessonSummaryReader {
    @PersistenceContext private EntityManager entities;
    @Override public Map<UUID, Summary> current(Collection<UUID> ids, Instant at) {
        if (ids.isEmpty()) return Map.of();
        // Scalar projection avoids stale managed objects after the publisher switches current versions.
        var rows = entities.createQuery("""
                select v.lessonId,v.id,v.title,v.type from LearningLessonVersion v
                where v.lessonId in :ids and v.current=true and v.publishedAt<=:at and v.revokedAt is null
                """, Object[].class).setParameter("ids", ids).setParameter("at", at).getResultList();
        Map<UUID, Summary> result = new HashMap<>();
        for (var row : rows) {
            var summary = new Summary((UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3]);
            result.put(summary.lessonId(), summary);
        }
        return Map.copyOf(result);
    }
}
