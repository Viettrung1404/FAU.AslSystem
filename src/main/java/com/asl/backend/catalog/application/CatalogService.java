package com.asl.backend.catalog.application;
import com.asl.backend.catalog.application.api.*;
import com.asl.backend.catalog.application.port.out.*;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import java.time.Clock;
import java.util.*;
import java.net.URI;
import com.asl.backend.catalog.domain.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import static com.asl.backend.platform.application.api.ApplicationFailure.Kind.*;
public final class CatalogService implements CatalogApi {
    private final CatalogRepository repository;
    private final LessonSummaryReader lessons;
    private final CatalogCursor cursors;
    private final boolean prerequisites;
    private final String supportUrl;
    private final Clock clock;
    public CatalogService(CatalogRepository repository, LessonSummaryReader lessons, CatalogCursor cursors,
                          boolean prerequisites, String supportUrl, Clock clock) {
        this.repository = repository; this.lessons = lessons; this.cursors = cursors;
        this.prerequisites = prerequisites; this.supportUrl = supportUrl; this.clock = clock;
    }

    @Override public CatalogData.Config config() {
        URI support;
        try {
            support = URI.create(supportUrl == null ? "" : supportUrl);
            String host = support.getHost() == null ? "" : support.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (!"https".equalsIgnoreCase(support.getScheme()) || host.isEmpty() || host.equals("invalid")
                    || host.endsWith(".invalid") || support.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
        var p = repository.policy();
        if (p == null || !"v1".equals(p.apiVersion()) || !"0.3.0".equals(p.contractVersion())
                || p.dailyLessonLimit() != null || !Integer.valueOf(3).equals(p.practiceMinimumDistinctLessons())
                || !Boolean.FALSE.equals(p.offlineLearningSupported()) || p.practiceModes() == null
                || p.practiceModes().size() != 4 || !new HashSet<>(p.practiceModes()).equals(
                        Set.of("QUIZ", "FLASHCARDS", "SIGN_MATCHING", "ALPHABET_MATCHING"))) throw unavailable();
        var s = p.scoringPolicy();
        if (s == null || !Integer.valueOf(1).equals(s.version()) || !"FIRST_ATTEMPT_ONLY".equals(s.ordinaryQuestions())
                || !"EACH_CORRECT_PAIR_ONCE".equals(s.matching()) || !Integer.valueOf(0).equals(s.reviewPoints())
                || !"LESSON_COMPLETED".equals(s.commitOn()) || !Boolean.TRUE.equals(s.replayEarnsPoints())) throw unavailable();
        return new CatalogData.Config(p.apiVersion(), p.contractVersion(), null, p.practiceMinimumDistinctLessons(),
                p.practiceModes(), p.offlineLearningSupported(), new CatalogData.Scoring(s.version(), s.ordinaryQuestions(),
                s.matching(), s.reviewPoints(), s.commitOn(), s.replayEarnsPoints()), support);
    }

    @Override public CatalogData.Page units(String cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor.isBlank())) throw badRequest();
        var after = cursor == null ? null : cursors.decode(cursor, prerequisites);
        var now = clock.instant();
        var found = repository.findUnits(after, limit + 1, now);
        boolean more = found.size() > limit;
        var page = more ? found.subList(0, limit) : found;
        String next = null;
        if (more) {
            var last = page.getLast();
            next = cursors.encode(new UnitPosition(last.position(), last.id()), prerequisites);
        }
        return new CatalogData.Page(project(page, now), next);
    }

    @Override public CatalogData.Unit unit(UUID id) {
        if (id == null) throw badRequest();
        var now = clock.instant();
        var unit = repository.findUnit(id, now).orElseThrow(() ->
                new ApplicationFailure(NOT_FOUND, "NOT_FOUND", "Unit not found"));
        return project(List.of(unit), now).getFirst();
    }

    private List<CatalogData.Unit> project(List<UnitReference> units, java.time.Instant at) {
        if (units.isEmpty()) return List.of();
        var references = repository.findLessons(units.stream().map(UnitReference::id).toList());
        var current = references.isEmpty() ? Map.<UUID, LessonSummaryReader.Summary>of()
                : lessons.current(references.stream().map(LessonReference::id).toList(), at);
        Map<UUID, List<CatalogData.Lesson>> grouped = new HashMap<>();
        references.stream().sorted(Comparator.comparingInt(LessonReference::position)
                .thenComparing(ref -> ref.id().toString())).forEach(ref -> {
            var summary = current.get(ref.id());
            if (summary == null) return;
            var effective = prerequisites ? ref.prerequisites().stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()
                    : List.<UUID>of();
            if (effective.size() > 10000) throw unavailable();
            var target = grouped.computeIfAbsent(ref.unitId(), ignored -> new ArrayList<>());
            if (target.size() >= 500) throw unavailable();
            target.add(new CatalogData.Lesson(ref.id(), summary.title(), summary.type(), ref.position(), effective, summary.versionId()));
        });
        return units.stream().map(unit -> new CatalogData.Unit(unit.id(), unit.title(), unit.position(),
                grouped.getOrDefault(unit.id(), List.of()))).toList();
    }

    private static ApplicationFailure badRequest() { return new ApplicationFailure(BAD_REQUEST, "BAD_REQUEST", "Invalid catalog request"); }
    private static ApplicationFailure unavailable() { return new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Catalog configuration unavailable"); }
}
