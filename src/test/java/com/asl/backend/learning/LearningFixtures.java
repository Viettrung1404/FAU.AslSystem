package com.asl.backend.learning;

import com.asl.backend.learning.application.port.out.LessonRepository.*;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class LearningFixtures {
    static final List<Map<String, Object>> CONTENT;
    static {
        try (var stream = LearningFixtures.class.getResourceAsStream("/development/lessons.json")) {
            CONTENT = JsonMapper.builder().build().readValue(stream, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static UUID uuid(Object value) { return UUID.fromString(value.toString()); }
    @SuppressWarnings("unchecked")
    static Version version(int index) {
        var c = CONTENT.get(index);
        var signs = ((List<Map<String,Object>>) c.get("signs")).stream().map(s -> new Sign(uuid(s.get("id")), (String)s.get("name"), uuid(((Map<?, ?>)s.get("video")).get("assetId")))).toList();
        var exercises = ((List<Map<String,Object>>)c.get("exercises")).stream().map(e -> {
            Map<String,UUID> slots = new HashMap<>(); ((Map<String,Map<String,Object>>)e.get("media")).forEach((slot,m) -> slots.put(slot, uuid(m.get("assetId"))));
            return new Exercise(uuid(e.get("exerciseId")), (Integer)e.get("position"), (String)e.get("type"), (String)e.get("prompt"),
                (Map<String,Object>)e.get("payload"), (Map<String,Object>)e.get("answerKey"), (Integer)e.get("pointsPerCorrect"),
                ((List<String>)e.get("signIds")).stream().map(UUID::fromString).toList(), slots);
        }).toList();
        var turns = ((List<Map<String,Object>>)c.get("conversationTurns")).stream().map(t -> new Turn(uuid(t.get("turnId")), (Integer)t.get("position"),
                (String)t.get("speakerSide"), (String)t.get("glossCaption"), (String)t.get("englishTranslation"), uuid(((Map<?, ?>)t.get("video")).get("assetId")))).toList();
        return new Version(uuid(c.get("lessonId")), uuid(c.get("versionId")), (String)c.get("title"), (String)c.get("type"), java.time.Instant.parse("2026-01-01T00:00:00Z"), null, signs, exercises, turns);
    }
}
