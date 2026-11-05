package com.asl.backend.learning.application.api;
import java.util.*;

/** Frozen metadata only; availability does not require media delivery configuration. */
public interface PracticeContentReader {
    record Source(UUID lessonId,UUID versionId) { }
    record Sign(UUID id,String name,UUID videoAssetId,UUID imageAssetId,boolean alphabet) { }
    List<Sign> signs(Source source);
}
