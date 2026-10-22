package com.asl.backend.learning.application.api;
import java.time.Instant;
import java.util.*;

/** Read-only summary seam for catalog; content retrieval/publishing belongs to module 03. */
public interface LessonSummaryReader {
    Map<UUID, Summary> current(Collection<UUID> lessonIds, Instant at);
    record Summary(UUID lessonId, UUID versionId, String title, String type) { }
}
