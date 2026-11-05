package com.asl.backend.learning.application.port.out;

import java.time.Instant;
import java.util.*;

public interface LessonRepository {
    Optional<Version> find(UUID lessonId, UUID versionId, Instant at);
    Version draftForUpdate(UUID versionId);
    void publish(UUID versionId, Instant at);
    void revoke(UUID versionId, Instant at);

    record Version(UUID lessonId, UUID versionId, String title, String type,
                   Instant publishedAt, Instant revokedAt, List<Sign> signs,
                   List<Exercise> exercises, List<Turn> turns) { }
    record Sign(UUID id, String name, UUID assetId, String kind) {
        public Sign(UUID id, String name, UUID assetId) { this(id, name, assetId, "WORD"); }
    }
    record Exercise(UUID id, int position, String type, String prompt,
                    Map<String, Object> payload, Map<String, Object> answerKey,
                    int points, List<UUID> signIds, Map<String, UUID> media) { }
    record Turn(UUID id, int position, String side, String gloss, String translation, UUID assetId) { }
}
