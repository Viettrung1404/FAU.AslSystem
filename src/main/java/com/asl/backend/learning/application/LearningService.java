package com.asl.backend.learning.application;

import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.learning.application.port.out.LessonRepository.*;
import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;

public final class LearningService implements LearningApi {
    private final LessonRepository repository;
    private final MediaApi media;
    private final Clock clock;
    public LearningService(LessonRepository repository, MediaApi media, Clock clock) {
        this.repository = repository; this.media = media; this.clock = clock;
    }
    @Override public Map<String, Object> content(UUID lessonId, UUID versionId) {
        var now = clock.instant();
        var version = repository.find(lessonId, versionId, now).orElseThrow(LearningService::missing);
        if (version.publishedAt() == null || version.publishedAt().isAfter(now)) throw missing();
        if (version.revokedAt() != null) throw new ApplicationFailure(ApplicationFailure.Kind.REMOVED,
                "CONTENT_VERSION_RETIRED", "This lesson version was retired; explicitly restart with the current version");
        var urls = urls(version);
        return Map.of("lessonId", version.lessonId(), "versionId", version.versionId(), "title", version.title(),
                "type", version.type(), "signs", version.signs().stream().map(s -> Map.of("id", s.id(), "name", s.name(), "video", urls.get(s.assetId()))).toList(),
                "exercises", version.exercises().stream().map(e -> exercise(e, urls)).toList(),
                "conversationTurns", version.turns().stream().map(t -> Map.of("turnId", t.id(), "position", t.position(),
                        "speakerSide", t.side(), "glossCaption", t.gloss(), "englishTranslation", t.translation(), "video", urls.get(t.assetId()))).toList(),
                "scoringPolicy", Map.of("version", 1, "ordinaryQuestions", "FIRST_ATTEMPT_ONLY", "matching", "EACH_CORRECT_PAIR_ONCE",
                        "reviewPoints", 0, "commitOn", "LESSON_COMPLETED", "replayEarnsPoints", true));
    }
    private Map<UUID, MediaItem> urls(Version version) {
        Set<UUID> ids = new LinkedHashSet<>();
        version.signs().forEach(s -> ids.add(s.assetId()));
        version.exercises().forEach(e -> ids.addAll(e.media().values()));
        version.turns().forEach(t -> ids.add(t.assetId()));
        var all = new ArrayList<>(ids); Map<UUID, MediaItem> result = new HashMap<>();
        for (int i = 0; i < all.size(); i += 100) media.refresh(all.subList(i, Math.min(i + 100, all.size()))).forEach(m -> result.put(m.assetId(), m));
        return result;
    }
    private Map<String, Object> exercise(Exercise e, Map<UUID, MediaItem> urls) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exerciseId", e.id()); result.put("position", e.position()); result.put("type", e.type()); result.put("prompt", e.prompt());
        result.put("payload", e.payload()); result.put("answerKey", e.answerKey()); result.put("pointsPerCorrect", e.points()); result.put("signIds", e.signIds());
        Map<String, MediaItem> slots = new LinkedHashMap<>(); e.media().forEach((key, id) -> slots.put(key, urls.get(id)));
        result.put("media", slots); return result;
    }
    @Override public void publish(UUID id) {
        var version = repository.draftForUpdate(id);
        if (version.revokedAt() != null) throw new ApplicationFailure(ApplicationFailure.Kind.REMOVED, "CONTENT_VERSION_RETIRED", "Revocation is permanent");
        if (version.publishedAt() != null) return;
        if (version.exercises().size() > 500 || version.turns().size() > 500 || version.signs().size() > 500 ||
                (version.type().equals("CONVERSATION") ? version.turns().isEmpty() || !version.exercises().isEmpty() : version.exercises().isEmpty() || !version.turns().isEmpty()))
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "INVALID_CONTENT", "Content does not match lesson type or bounds");
        // Resolve every referenced asset before publication; never auto-publish somebody else's draft media.
        LessonContentRules.validate(version);
        urls(version);
        repository.publish(id, clock.instant());
    }
    @Override public void revoke(UUID id) { repository.revoke(id, clock.instant()); }
    public static ApplicationFailure missing() { return new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "NOT_FOUND", "Published lesson version not found"); }
}
