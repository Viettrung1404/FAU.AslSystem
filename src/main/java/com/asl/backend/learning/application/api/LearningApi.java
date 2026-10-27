package com.asl.backend.learning.application.api;

import java.util.Map;
import java.util.UUID;

/** Complete immutable content and public local answer keys; prerequisites are UI only. */
public interface LearningApi {
    Map<String, Object> content(UUID lessonId, UUID versionId);
    void publish(UUID versionId);
    void revoke(UUID versionId);
}
