package com.asl.backend.catalog.adapter.in.web;
import com.asl.backend.catalog.application.api.CatalogApi;
import com.asl.backend.catalog.application.api.CatalogData;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class CatalogController {
    private final CatalogApi api;
    public CatalogController(CatalogApi api) { this.api = api; }
    @GetMapping("/api/v1/config")
    public ConfigResponse config() {
        var c = api.config(); var s = c.scoringPolicy();
        return new ConfigResponse(c.apiVersion(), c.contractVersion(), c.dailyLessonLimit(), c.practiceMinimumDistinctLessons(),
                c.practiceModes(), c.offlineLearningSupported(), new ScoringResponse(s.version(), s.ordinaryQuestions(), s.matching(),
                s.reviewPoints(), s.commitOn(), s.replayEarnsPoints()), c.supportUrl());
    }
    @GetMapping("/api/v1/home")
    public HomeResponse home(@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return new HomeResponse(page(api.units(cursor, limit(limit))));
    }
    @GetMapping("/api/v1/units")
    public UnitsResponse units(@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return page(api.units(cursor, limit(limit)));
    }
    @GetMapping("/api/v1/units/{unitId}")
    public UnitResponse unit(@PathVariable String unitId) {
        if (!unitId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw badRequest();
        return unit(api.unit(UUID.fromString(unitId)));
    }
    private static int limit(String value) {
        if (value == null) return 20;
        try {
            if (!value.matches("[0-9]{1,3}")) throw badRequest();
            return Integer.parseInt(value);
        } catch (NumberFormatException error) { throw badRequest(); }
    }
    private static UnitsResponse page(CatalogData.Page page) {
        return new UnitsResponse(page.items().stream().map(CatalogController::unit).toList(), page.nextCursor());
    }
    private static UnitResponse unit(CatalogData.Unit unit) {
        return new UnitResponse(unit.id(), unit.title(), unit.position(), unit.lessons().stream().map(lesson ->
                new LessonResponse(lesson.id(), lesson.title(), lesson.type(), lesson.position(), lesson.prerequisiteLessonIds(), lesson.currentVersionId())).toList());
    }
    private static ApplicationFailure badRequest() { return new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Invalid catalog query or ID"); }
    public record HomeResponse(UnitsResponse units) { }
    public record UnitsResponse(List<UnitResponse> items, String nextCursor) { }
    public record UnitResponse(UUID id, String title, int position, List<LessonResponse> lessons) { }
    public record LessonResponse(UUID id, String title, String type, int position, List<UUID> prerequisiteLessonIds, UUID currentVersionId) { }
    public record ConfigResponse(String apiVersion, String contractVersion, Integer dailyLessonLimit, int practiceMinimumDistinctLessons,
            List<String> practiceModes, boolean offlineLearningSupported, ScoringResponse scoringPolicy, URI supportUrl) { }
    public record ScoringResponse(int version, String ordinaryQuestions, String matching, int reviewPoints, String commitOn, boolean replayEarnsPoints) { }
}
