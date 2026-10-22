package com.asl.backend.catalog.application.api;
import java.net.URI;
import java.util.*;

public final class CatalogData {
    private CatalogData() { }
    public record Page(List<Unit> items, String nextCursor) { public Page { items = List.copyOf(items); } }
    public record Unit(UUID id, String title, int position, List<Lesson> lessons) { public Unit { lessons = List.copyOf(lessons); } }
    public record Lesson(UUID id, String title, String type, int position, List<UUID> prerequisiteLessonIds, UUID currentVersionId) {
        public Lesson { prerequisiteLessonIds = List.copyOf(prerequisiteLessonIds); }
    }
    public record Config(String apiVersion, String contractVersion, Integer dailyLessonLimit, int practiceMinimumDistinctLessons,
                         List<String> practiceModes, boolean offlineLearningSupported, Scoring scoringPolicy, URI supportUrl) {
        public Config { practiceModes = List.copyOf(practiceModes); }
    }
    public record Scoring(int version, String ordinaryQuestions, String matching, int reviewPoints, String commitOn, boolean replayEarnsPoints) { }
}
