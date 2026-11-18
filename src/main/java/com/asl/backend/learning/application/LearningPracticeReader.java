package com.asl.backend.learning.application;
import com.asl.backend.learning.application.api.PracticeContentReader;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LearningPracticeReader implements PracticeContentReader {
    private final LessonRepository repository;
    private final Clock clock;
    private final Map<Source, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(10);

    private record CacheEntry(Instant cachedAt, List<Sign> signs) {}

    public LearningPracticeReader(LessonRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override public List<Sign> signs(Source source) {
        var now = clock.instant();
        var cached = cache.get(source);
        if (cached != null && Duration.between(cached.cachedAt(), now).compareTo(TTL) < 0) {
            return cached.signs();
        }
        var v = repository.find(source.lessonId(), source.versionId(), now).orElseThrow(LearningPracticeReader::invalid);
        if (v.publishedAt() == null || v.publishedAt().isAfter(now)) throw invalid();
        if (v.revokedAt() != null) throw new ApplicationFailure(ApplicationFailure.Kind.REMOVED, "CONTENT_VERSION_RETIRED", "A source lesson version has been withdrawn");
        Map<UUID, UUID> images = new HashMap<>();
        for (var e : v.exercises()) {
            if (!e.type().equals("VIDEO_TO_IMAGE") || e.answerKey() == null) continue;
            var video = e.media().get(e.payload().get("videoSlot"));
            var targets = v.signs().stream().filter(s -> s.assetId().equals(video) && e.signIds().contains(s.id())).toList();
            if (targets.size() != 1 || !(e.payload().get("options") instanceof List<?> options)) continue;
            for (var option : options) if (option instanceof Map<?, ?> o && Objects.equals(o.get("id"), e.answerKey().get("correctOptionId"))) {
                var image = e.media().get(o.get("imageSlot")); if (image != null) images.putIfAbsent(targets.getFirst().id(), image);
            }
        }
        var signs = v.signs().stream().map(s -> new Sign(s.id(), s.name(), s.assetId(), images.get(s.id()), s.kind().equals("LETTER"))).toList();
        cache.put(source, new CacheEntry(now, signs));
        return signs;
    }
    private static ApplicationFailure invalid() { return new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "VALIDATION_FAILED", "Unknown, mismatched or unpublished lesson/version pair"); }
}
