package com.asl.backend.learning.adapter.out.persistence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Read mapping for current summaries only; does not implement authoring or content retrieval. */
@Entity(name = "LearningLessonVersion")
@Table(name = "lesson_versions", schema = "asl")
public class LessonVersionEntity {
    @Id private UUID id;
    @Column(name = "lesson_id", nullable = false) private UUID lessonId;
    @Column(nullable = false) private String title;
    @Column(name = "lesson_type", nullable = false) private String type;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "is_current", nullable = false) private boolean current;
    protected LessonVersionEntity() { }
}
