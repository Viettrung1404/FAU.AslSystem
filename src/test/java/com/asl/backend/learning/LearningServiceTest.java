package com.asl.backend.learning;

import com.asl.backend.learning.application.LearningService;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.learning.application.port.out.LessonRepository.*;
import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LearningServiceTest {
    LessonRepository repository = mock(LessonRepository.class);
    MediaApi media = mock(MediaApi.class);
    LearningService service = new LearningService(repository, media, Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC));
    @BeforeEach void urls() {
        when(media.refresh(anyList())).thenAnswer(call -> ((List<UUID>)call.getArgument(0)).stream().map(id ->
            new MediaItem(id, URI.create("https://cdn.example.org/"+id), null, "video/mp4", 2000)).toList());
    }
    @Test void allThirteenTypesAndNullAnswerKeysAreDeliveredWithoutLosingPayload() {
        var v = LearningFixtures.version(0); when(repository.find(eq(v.lessonId()), isNull(), any())).thenReturn(Optional.of(v));
        var result = service.content(v.lessonId(), null);
        var exercises = (List<Map<String,Object>>)result.get("exercises");
        assertThat(exercises).hasSize(13);
        for (int i=0; i<13; i++) {
            assertThat(exercises.get(i).get("payload")).isEqualTo(v.exercises().get(i).payload());
            assertThat(exercises.get(i).get("answerKey")).isEqualTo(v.exercises().get(i).answerKey());
        }
        verify(media).refresh(argThat(ids -> ids.size() == new HashSet<>(ids).size()));
    }
    @Test void retainedVersionAndConversationDoNotRequireCatalogOrProgress() {
        var v = LearningFixtures.version(3); when(repository.find(eq(v.lessonId()), eq(v.versionId()), any())).thenReturn(Optional.of(v));
        var result = service.content(v.lessonId(), v.versionId());
        assertThat((List<?>)result.get("exercises")).isEmpty(); assertThat((List<?>)result.get("conversationTurns")).hasSize(2);
    }
    @Test void missingDraftFutureAndRevokedVersionsHaveDistinctFailuresBeforeMedia() {
        var v = LearningFixtures.version(0);
        when(repository.find(any(), any(), any())).thenReturn(Optional.empty());
        code("NOT_FOUND", () -> service.content(v.lessonId(), v.versionId()));
        for (Instant publication : Arrays.asList(null, Instant.parse("2030-01-01T00:00:00Z"))) {
            when(repository.find(any(), any(), any())).thenReturn(Optional.of(new Version(v.lessonId(), v.versionId(), v.title(), v.type(), publication, null, v.signs(), v.exercises(), v.turns())));
            code("NOT_FOUND", () -> service.content(v.lessonId(), v.versionId()));
        }
        when(repository.find(any(), any(), any())).thenReturn(Optional.of(new Version(v.lessonId(), v.versionId(), v.title(), v.type(), v.publishedAt(), Instant.now(), v.signs(), v.exercises(), v.turns())));
        code("CONTENT_VERSION_RETIRED", () -> service.content(v.lessonId(), v.versionId()));
        verifyNoInteractions(media);
    }
    @Test void publisherChecksAllTypesAndRejectsBrokenKeysBeforePublishing() {
        var v = LearningFixtures.version(0);
        var draft = new Version(v.lessonId(), v.versionId(), v.title(), v.type(), null, null, v.signs(), v.exercises(), v.turns());
        when(repository.draftForUpdate(v.versionId())).thenReturn(draft);
        service.publish(v.versionId()); verify(repository).publish(eq(v.versionId()), any());
        var first = v.exercises().getFirst();
        var broken = new Exercise(first.id(), 0, first.type(), first.prompt(), first.payload(), Map.of("correctOptionId", "missing"), 10, first.signIds(), first.media());
        when(repository.draftForUpdate(v.versionId())).thenReturn(new Version(v.lessonId(), v.versionId(), v.title(), v.type(), null, null, v.signs(), List.of(broken), List.of()));
        code("INVALID_CONTENT", () -> service.publish(v.versionId()));
        verify(repository, times(1)).publish(any(), any());
    }
    static void code(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.code()).isEqualTo(code)); }
}
