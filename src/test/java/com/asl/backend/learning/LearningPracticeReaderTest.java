package com.asl.backend.learning;
import com.asl.backend.learning.application.LearningPracticeReader;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.learning.application.port.out.LessonRepository.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LearningPracticeReaderTest {
    final LessonRepository repository=mock(LessonRepository.class);
    final Instant now=Instant.parse("2026-11-05T00:00:00Z");
    final LearningPracticeReader reader=new LearningPracticeReader(repository,Clock.fixed(now,ZoneOffset.UTC));
    @Test void onlyExplicitRetainedVersionAndAuthoredCorrectImageAreUsed() {
        UUID lesson=UUID.randomUUID(),old=UUID.randomUUID(),sid=UUID.randomUUID(),video=UUID.randomUUID(),image=UUID.randomUUID();
        var exercise=new Exercise(UUID.randomUUID(),0,"VIDEO_TO_IMAGE","",Map.of("videoSlot","video","options",List.of(Map.of("id","correct","imageSlot","image"))),Map.of("correctOptionId","correct"),10,List.of(sid),Map.of("video",video,"image",image));
        var version=new Version(lesson,old,"Old","LESSON",now.minusSeconds(1),null,List.of(new Sign(sid,"Old A",video,"LETTER")),List.of(exercise),List.of());
        when(repository.find(lesson,old,now)).thenReturn(Optional.of(version));
        var signs=reader.signs(new Source(lesson,old));
        assertThat(signs).hasSize(1); assertThat(signs.getFirst().name()).isEqualTo("Old A");
        assertThat(signs.getFirst().alphabet()).isTrue(); assertThat(signs.getFirst().imageAssetId()).isEqualTo(image);
        verify(repository).find(lesson,old,now); verifyNoMoreInteractions(repository);
    }
    @Test void missingDraftFutureAndRevokedHaveDistinctFailures() {
        var source=new Source(UUID.randomUUID(),UUID.randomUUID());
        when(repository.find(any(),any(),any())).thenReturn(Optional.empty()); code("VALIDATION_FAILED",()->reader.signs(source));
        for(Instant stamp:Arrays.asList(null,now.plusSeconds(60))) {
            when(repository.find(any(),any(),any())).thenReturn(Optional.of(new Version(source.lessonId(),source.versionId(),"Draft","LESSON",stamp,null,List.of(),List.of(),List.of())));
            code("VALIDATION_FAILED",()->reader.signs(source));
        }
        when(repository.find(any(),any(),any())).thenReturn(Optional.of(new Version(source.lessonId(),source.versionId(),"Retired","LESSON",now.minusSeconds(1),now,List.of(),List.of(),List.of())));
        code("CONTENT_VERSION_RETIRED",()->reader.signs(source));
    }
    private static void code(String code,Runnable work) { assertThatThrownBy(work::run).isInstanceOfSatisfying(ApplicationFailure.class,e->assertThat(e.code()).isEqualTo(code)); }
}
