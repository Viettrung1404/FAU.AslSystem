package com.asl.backend.catalog.domain;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;
public record LessonReference(UUID unitId, UUID id, int position, List<UUID> prerequisites) implements Serializable {
    public LessonReference { prerequisites = List.copyOf(prerequisites); }
}
