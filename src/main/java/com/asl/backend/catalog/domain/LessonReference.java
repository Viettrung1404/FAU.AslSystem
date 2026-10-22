package com.asl.backend.catalog.domain;
import java.util.List;
import java.util.UUID;
public record LessonReference(UUID unitId, UUID id, int position, List<UUID> prerequisites) {
    public LessonReference { prerequisites = List.copyOf(prerequisites); }
}
