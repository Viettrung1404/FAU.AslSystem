package com.asl.backend.catalog.domain;
import java.util.List;
public record CatalogPolicy(String apiVersion, String contractVersion, Integer dailyLessonLimit,
        Integer practiceMinimumDistinctLessons, List<String> practiceModes, Boolean offlineLearningSupported, Scoring scoringPolicy) {
    public record Scoring(Integer version, String ordinaryQuestions, String matching, Integer reviewPoints,
                          String commitOn, Boolean replayEarnsPoints) { }
}
