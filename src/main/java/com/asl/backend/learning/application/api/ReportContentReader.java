package com.asl.backend.learning.application.api;
import java.util.*;
/** Historical references for reporting; does not require currently deliverable media. */
public interface ReportContentReader {
    boolean lessonExists(UUID lessonId);
    boolean exerciseExists(UUID exerciseId, UUID versionId);
    record Source(UUID lessonId, UUID versionId) { }
    Map<Source,Set<UUID>> sourceSigns(List<Source> sources);
}
