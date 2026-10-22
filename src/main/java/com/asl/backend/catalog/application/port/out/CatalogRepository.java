package com.asl.backend.catalog.application.port.out;
import com.asl.backend.catalog.domain.*;
import java.time.Instant;
import java.util.*;
public interface CatalogRepository {
    List<UnitReference> findUnits(UnitPosition after, int count, Instant at);
    Optional<UnitReference> findUnit(UUID id, Instant at);
    List<LessonReference> findLessons(Collection<UUID> unitIds);
    CatalogPolicy policy();
}
