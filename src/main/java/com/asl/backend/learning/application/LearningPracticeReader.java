package com.asl.backend.learning.application;
import com.asl.backend.learning.application.api.PracticeContentReader;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;

public final class LearningPracticeReader implements PracticeContentReader {
    private final LessonRepository repository; private final Clock clock;
    public LearningPracticeReader(LessonRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    @Override public List<Sign> signs(Source source) {
        var v=repository.find(source.lessonId(),source.versionId(),clock.instant()).orElseThrow(LearningPracticeReader::invalid);
        if (v.publishedAt()==null || v.publishedAt().isAfter(clock.instant())) throw invalid();
        if (v.revokedAt()!=null) throw new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_VERSION_RETIRED","A source lesson version has been withdrawn");
        Map<UUID,UUID> images=new HashMap<>();
        for (var e:v.exercises()) {
            if (!e.type().equals("VIDEO_TO_IMAGE") || e.answerKey()==null) continue;
            var video=e.media().get(e.payload().get("videoSlot"));
            var targets=v.signs().stream().filter(s -> s.assetId().equals(video) && e.signIds().contains(s.id())).toList();
            if (targets.size()!=1 || !(e.payload().get("options") instanceof List<?> options)) continue;
            for (var option:options) if (option instanceof Map<?,?> o && Objects.equals(o.get("id"),e.answerKey().get("correctOptionId"))) {
                var image=e.media().get(o.get("imageSlot")); if (image!=null) images.putIfAbsent(targets.getFirst().id(),image);
            }
        }
        return v.signs().stream().map(s -> new Sign(s.id(),s.name(),s.assetId(),images.get(s.id()),s.kind().equals("LETTER"))).toList();
    }
    private static ApplicationFailure invalid() { return new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Unknown, mismatched or unpublished lesson/version pair"); }
}
