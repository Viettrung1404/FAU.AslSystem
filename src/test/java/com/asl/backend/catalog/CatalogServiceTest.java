package com.asl.backend.catalog;

import com.asl.backend.catalog.application.CatalogService;
import com.asl.backend.catalog.application.port.out.*;
import com.asl.backend.catalog.domain.*;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogServiceTest {
    static final Instant NOW = Instant.parse("2026-10-22T05:00:00Z");
    final MemoryCatalog store = new MemoryCatalog();
    final Map<UUID, LessonSummaryReader.Summary> summaries = new HashMap<>();
    final CatalogCursor cursors = new CatalogCursor() {
        public String encode(UnitPosition position, boolean prerequisites) { return position.position() + ":" + position.id(); }
        public UnitPosition decode(String value, boolean prerequisites) {
            var parts = value.split(":"); return new UnitPosition(Integer.parseInt(parts[0]), UUID.fromString(parts[1]));
        }
    };
    CatalogService service(boolean prerequisites) { return service(prerequisites, "https://support.example.org/asl"); }
    CatalogService service(boolean prerequisites, String support) {
        return new CatalogService(store, (ids, at) -> {
            Map<UUID, LessonSummaryReader.Summary> result = new HashMap<>();
            ids.forEach(id -> { if (summaries.containsKey(id)) result.put(id, summaries.get(id)); });
            return result;
        }, cursors, prerequisites, support, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void pageUsesKeysetOrderAndNullCursorAtTheEnd() {
        var third = unit(3); var first = unit(1); var second = unit(2);
        var api = service(true);
        var page = api.units(null, 2);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(first.id(), second.id());
        assertThat(page.nextCursor()).isNotNull();
        var last = api.units(page.nextCursor(), 1);
        assertThat(last.items()).extracting(item -> item.id()).containsExactly(third.id());
        assertThat(last.nextCursor()).isNull();
    }

    @Test void emptyCatalogIsARealEmptyPage() {
        var page = service(true).units(null, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test void summaryUsesCurrentVersionDataAndSkipsUnavailableLessons() {
        var unit = unit(0);
        var first = lesson(unit.id(), 0, "LESSON", List.of());
        var second = lesson(unit.id(), 1, "ALPHABET", List.of(first.id()));
        store.lessons.add(new LessonReference(unit.id(), UUID.randomUUID(), 2, List.of()));
        var result = service(true).unit(unit.id());
        assertThat(result.lessons()).extracting(item -> item.id()).containsExactly(first.id(), second.id());
        assertThat(result.lessons().get(1).title()).isEqualTo("Published ALPHABET");
        assertThat(result.lessons().get(1).currentVersionId()).isEqualTo(summaries.get(second.id()).versionId());
    }

    @Test void disabledPrerequisitesApplyToEveryTypeAndUnitWithoutChangingStoredData() {
        UUID previous = UUID.randomUUID();
        for (String type : List.of("LESSON", "ALPHABET", "REVIEW", "CONVERSATION")) {
            var unit = unit(store.units.size()); lesson(unit.id(), 0, type, List.of(previous));
        }
        var api = service(false);
        assertThat(api.units(null, 100).items()).hasSize(4);
        assertThat(api.units(null, 100).items()).allSatisfy(unit ->
                assertThat(unit.lessons()).allSatisfy(lesson -> assertThat(lesson.prerequisiteLessonIds()).isEmpty()));
        for (var unit : store.units) assertThat(api.unit(unit.id()).lessons().getFirst().prerequisiteLessonIds()).isEmpty();
        assertThat(store.lessons).allSatisfy(lesson -> assertThat(lesson.prerequisites()).containsExactly(previous));
        assertThat(service(true).units(null, 100).items()).allSatisfy(unit ->
                assertThat(unit.lessons().getFirst().prerequisiteLessonIds()).containsExactly(previous));
    }

    @Test void unknownUnitReturnsNotFound() {
        assertCode("NOT_FOUND", () -> service(true).unit(UUID.randomUUID()));
    }

    @Test void queryBoundsAndEmptyCursorAreBadRequest() {
        for (int limit : new int[]{0, -1, 101}) assertCode("BAD_REQUEST", () -> service(true).units(null, limit));
        assertCode("BAD_REQUEST", () -> service(true).units("", 20));
        assertCode("BAD_REQUEST", () -> service(true).unit(null));
    }

    @Test void configReturnsStoredVersionedPolicyAndRealConfiguredSupportUrl() {
        var config = service(true).config();
        assertThat(config).isNotNull();
        assertThat(config.contractVersion()).isEqualTo("0.3.0");
        assertThat(config.dailyLessonLimit()).isNull();
        assertThat(config.practiceMinimumDistinctLessons()).isEqualTo(3);
        assertThat(config.practiceModes()).containsExactly("QUIZ", "FLASHCARDS", "SIGN_MATCHING", "ALPHABET_MATCHING");
        assertThat(config.offlineLearningSupported()).isFalse();
        assertThat(config.scoringPolicy().replayEarnsPoints()).isTrue();
        assertThat(config.supportUrl().toString()).isEqualTo("https://support.example.org/asl");
    }

    @Test void missingOrInvalidSupportAndPolicyNeverBecomeSuccessfulMockConfig() {
        for (String support : Arrays.asList(null, "", "https://example.invalid/support", "https://cdn.invalid./s", "http://example.org/s", "https://user:secret@example.org")) {
            assertCode("SERVICE_UNAVAILABLE", () -> service(true, support).config());
        }
        store.policy = null;
        assertCode("SERVICE_UNAVAILABLE", () -> service(true).config());
        store.policy = new CatalogPolicy("v1", "0.2.0", null, 3, policy().practiceModes(), false, policy().scoringPolicy());
        assertCode("SERVICE_UNAVAILABLE", () -> service(true).config());
    }

    @Test void oversizedPublishedUnitFailsInsteadOfReturningAnInvalidOrTruncatedResponse() {
        var unit = unit(0);
        for (int i = 0; i < 501; i++) lesson(unit.id(), i, "LESSON", List.of());
        assertCode("SERVICE_UNAVAILABLE", () -> service(true).unit(unit.id()));
    }

    @Test void oversizedPrerequisiteListFailsOnlyWhenItMustBeExposed() {
        var unit = unit(0);
        var prerequisites = java.util.stream.IntStream.range(0, 10001).mapToObj(i -> UUID.randomUUID()).toList();
        lesson(unit.id(), 0, "LESSON", prerequisites);
        assertCode("SERVICE_UNAVAILABLE", () -> service(true).unit(unit.id()));
        assertThat(service(false).unit(unit.id()).lessons().getFirst().prerequisiteLessonIds()).isEmpty();
    }

    UnitReference unit(int position) {
        var unit = new UnitReference(UUID.randomUUID(), "Unit " + position, position);
        store.units.add(unit); return unit;
    }
    LessonReference lesson(UUID unit, int position, String type, List<UUID> prerequisites) {
        var ref = new LessonReference(unit, UUID.randomUUID(), position, prerequisites);
        store.lessons.add(ref);
        summaries.put(ref.id(), new LessonSummaryReader.Summary(ref.id(), UUID.randomUUID(), "Published " + type, type));
        return ref;
    }
    static CatalogPolicy policy() {
        return new CatalogPolicy("v1", "0.3.0", null, 3, List.of("QUIZ", "FLASHCARDS", "SIGN_MATCHING", "ALPHABET_MATCHING"), false,
                new CatalogPolicy.Scoring(1, "FIRST_ATTEMPT_ONLY", "EACH_CORRECT_PAIR_ONCE", 0, "LESSON_COMPLETED", true));
    }
    static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationFailure.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    static class MemoryCatalog implements CatalogRepository {
        final List<UnitReference> units = new ArrayList<>();
        final List<LessonReference> lessons = new ArrayList<>();
        CatalogPolicy policy = CatalogServiceTest.policy();
        public List<UnitReference> findUnits(UnitPosition after, int count, Instant at) {
            return units.stream().filter(unit -> after == null || unit.position() > after.position()
                    || (unit.position() == after.position() && unit.id().toString().compareTo(after.id().toString()) > 0))
                    .sorted(Comparator.comparingInt(UnitReference::position).thenComparing(unit -> unit.id().toString())).limit(count).toList();
        }
        public Optional<UnitReference> findUnit(UUID id, Instant at) { return units.stream().filter(unit -> unit.id().equals(id)).findFirst(); }
        public List<LessonReference> findLessons(Collection<UUID> ids) { return lessons.stream().filter(ref -> ids.contains(ref.unitId())).toList(); }
        public CatalogPolicy policy() { return policy; }
    }
}
