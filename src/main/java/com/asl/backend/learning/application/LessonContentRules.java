package com.asl.backend.learning.application;

import com.asl.backend.learning.application.port.out.LessonRepository.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;

/** Semantic references that a JSON discriminator alone cannot validate. */
final class LessonContentRules {
    private static final Set<String> MESSAGES = Set.of("ENCOURAGEMENT", "REVIEW_INTRO", "PRAISE");
    static void validate(Version version) {
        var signIds = new HashSet<>(version.signs().stream().map(Sign::id).toList());
        for (var e : version.exercises()) {
            var p = e.payload(); var key = e.answerKey();
            require(Objects.equals(p.get("schemaVersion"), 1));
            require(e.signIds().size() <= 100 && signIds.containsAll(e.signIds()));
            require(e.points() >= 0 && e.points() <= 1000);
            for (var entry : p.entrySet()) if (entry.getKey().endsWith("Slot")) require(e.media().containsKey(entry.getValue()));
            switch (e.type()) {
                case "VIDEO_TO_WORD", "VIDEO_TO_MEANING", "WORD_TO_VIDEO", "VIDEO_TO_IMAGE", "KNOWLEDGE" -> {
                    var ids = options(p.get("options"), e, 2, 8);
                    require(key != null && ids.contains(key.get("correctOptionId")));
                    if (e.type().equals("WORD_TO_VIDEO")) require(text(p.get("word")));
                    else if (e.type().equals("KNOWLEDGE")) require(text(p.get("question")));
                    else require(p.containsKey("videoSlot"));
                }
                case "FILL_GAP", "ORDER_SIGNS", "SPELL" -> {
                    var ids = options(p.get("bank"), e, 1, e.type().equals("FILL_GAP") ? 40 : 50);
                    require(key != null && p.containsKey("videoSlot"));
                    var answer = key.get(e.type().equals("FILL_GAP") ? "correctTokenIds" : "orderedTokenIds");
                    require(answer instanceof List<?>);
                    var tokens = (List<?>) answer;
                    require(!tokens.isEmpty() && tokens.size() <= 40 && ids.containsAll(tokens) && new HashSet<>(tokens).size() == tokens.size());
                    if (e.type().equals("FILL_GAP")) {
                        require(p.get("gapIndices") instanceof List<?> && p.get("glossTokens") instanceof List<?>);
                        var gaps = (List<?>) p.get("gapIndices"); var gloss = (List<?>) p.get("glossTokens");
                        require(gaps.size() == tokens.size() && new HashSet<>(gaps).size() == gaps.size());
                        for (var gap : gaps) require(gap instanceof Integer i && i >= 0 && i < gloss.size());
                    } else require(Objects.equals(p.get("slotCount"), tokens.size()));
                }
                case "MATCH_PAIRS" -> {
                    var left = options(p.get("left"), e, 2, 8); var right = options(p.get("right"), e, 2, 8);
                    require(key != null && key.get("pairs") instanceof Map<?, ?>);
                    var pairs = (Map<?, ?>) key.get("pairs");
                    require(pairs.keySet().equals(left) && new HashSet<>(pairs.values()).equals(right) && left.size() == right.size());
                }
                case "TIP" -> {
                    require(key == null && e.points() == 0 && text(p.get("title")) && p.get("rules") instanceof List<?> rules && !rules.isEmpty() && rules.size() <= 20 && rules.stream().allMatch(LessonContentRules::text));
                }
                default -> require(MESSAGES.contains(e.type()) && key == null && e.points() == 0 && text(p.get("text")));
            }
        }
    }
    private static Set<Object> options(Object value, Exercise e, int min, int max) {
        require(value instanceof List<?>);
        var options = (List<?>) value; require(options.size() >= min && options.size() <= max);
        Set<Object> ids = new HashSet<>();
        for (var option : options) {
            require(option instanceof Map<?, ?>); var item = (Map<?, ?>) option;
            require(item.get("id") instanceof String id && id.matches("[A-Za-z0-9_-]{1,64}") && ids.add(id));
            for (var entry : item.entrySet()) if (entry.getKey().toString().endsWith("Slot")) require(e.media().containsKey(entry.getValue()));
        }
        return ids;
    }
    private static boolean text(Object value) { return value instanceof String s && !s.isBlank() && s.length() <= 2000; }
    private static void require(boolean valid) {
        if (!valid) throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "INVALID_CONTENT", "Invalid exercise payload, answer key or content reference");
    }
}
