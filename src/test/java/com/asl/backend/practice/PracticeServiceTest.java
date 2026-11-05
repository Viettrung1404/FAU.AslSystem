package com.asl.backend.practice;

import com.asl.backend.practice.application.PracticeService;
import com.asl.backend.learning.application.api.PracticeContentReader;
import com.asl.backend.learning.application.api.PracticeContentReader.*;
import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PracticeServiceTest {
    final PracticeContentReader reader=mock(PracticeContentReader.class);
    final MediaApi media=mock(MediaApi.class);
    final List<Source> sources=List.of(new Source(UUID.randomUUID(),UUID.randomUUID()),new Source(UUID.randomUUID(),UUID.randomUUID()),new Source(UUID.randomUUID(),UUID.randomUUID()));
    final List<Sign> signs=new ArrayList<>();
    PracticeService service;
    @BeforeEach void setup() {
        for(int i=0;i<5;i++) signs.add(new Sign(UUID.randomUUID(),"Sign "+i,UUID.randomUUID(),UUID.randomUUID(),i<2));
        when(reader.signs(any())).thenReturn(List.of()); when(reader.signs(sources.getFirst())).thenAnswer(i->List.copyOf(signs));
        when(media.refresh(anyList())).thenAnswer(i->((List<UUID>)i.getArgument(0)).stream().map(id->new MediaItem(id,URI.create("https://cdn.example.org/"+id),null,signs.stream().anyMatch(s->id.equals(s.imageAssetId()))?"image/png":"video/mp4",null)).toList());
        service=new PracticeService(reader,media,Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),ZoneOffset.UTC),new Random(7),Duration.ofHours(1));
    }
    @Test void availabilityCountsDistinctLessonsAndDoesNotResolveMedia() {
        var extra=new Source(sources.getFirst().lessonId(),UUID.randomUUID());
        var result=service.availability(List.of(sources.getFirst(),extra,sources.get(1)));
        assertThat(result.get("completedDistinctLessons")).isEqualTo(2);
        for(var mode:(List<Map<String,Object>>)result.get("modes")) assertThat(mode).containsEntry("unlocked",false).containsEntry("canGenerate",false).containsEntry("reason","NEED_MORE_LESSONS");
        verifyNoInteractions(media);
    }
    @Test void invalidPairsAndLegacyShapesNeverBecomeCurrentVersionLookups() {
        code("VALIDATION_FAILED",()->service.availability(List.of(sources.getFirst(),sources.getFirst())));
        code("VALIDATION_FAILED",()->service.generate("BAD",10,sources));
        code("VALIDATION_FAILED",()->service.generate("QUIZ",31,sources));
        code("VALIDATION_FAILED",()->service.availability(Arrays.asList((Source)null)));
        doThrow(new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_VERSION_RETIRED","retired")).when(reader).signs(sources.getFirst());
        code("CONTENT_VERSION_RETIRED",()->service.availability(sources));
    }
    @Test void unlockedCanStillHaveInsufficientContent() {
        signs.clear(); var result=service.availability(sources);
        for(var mode:(List<Map<String,Object>>)result.get("modes")) assertThat(mode).containsEntry("unlocked",true).containsEntry("canGenerate",false).containsEntry("reason","INSUFFICIENT_CONTENT");
        code("INSUFFICIENT_CONTENT",()->service.generate("QUIZ",10,sources));
        code("NEED_MORE_LESSONS",()->service.generate("QUIZ",10,List.of()));
    }
    @Test void quizHasAllRenderersValidAnswersDistinctMeaningsAndOnlySourceMedia() {
        var set=service.generate("QUIZ",null,sources);
        assertThat(set).containsEntry("sourceLessons",sources).containsEntry("awardsLessonPoints",false).containsEntry("countsForStreak",false);
        var items=(List<Map<String,Object>>)set.get("items");
        assertThat(items).hasSize(10);
        assertThat(items.stream().map(i->i.get("type"))).contains("VIDEO_TO_WORD","WORD_TO_VIDEO","VIDEO_TO_IMAGE");
        for(var item:items) {
            var payload=(Map<String,Object>)item.get("payload"); var options=(List<Map<String,Object>>)payload.get("options");
            assertThat(payload.get("layout")).isEqualTo("GRID_2X2"); assertThat(options).hasSize(4);
            String correct=(String)((Map<?,?>)item.get("answerKey")).get("correctOptionId");
            assertThat(options.stream().filter(o->o.get("id").equals(correct)).findFirst().orElseThrow().get("signId")).isEqualTo(item.get("signId"));
            assertThat(options.stream().map(o->o.get("signId"))).doesNotHaveDuplicates().allMatch(id->signs.stream().anyMatch(s->s.id().equals(id)));
        }
    }
    @Test void missingImagesSkipOnlyImageQuestionsAndTwoSignsUseList() {
        signs.replaceAll(s->new Sign(s.id(),s.name(),s.videoAssetId(),null,s.alphabet()));
        signs.subList(2,signs.size()).clear();
        var items=(List<Map<String,Object>>)service.generate("QUIZ",30,sources).get("items");
        assertThat(items).hasSize(4).allSatisfy(item->{assertThat(item.get("type")).isNotEqualTo("VIDEO_TO_IMAGE"); assertThat(((Map<?,?>)item.get("payload")).get("layout")).isEqualTo("LIST");});
    }
    @Test void matchingIsBijectionAndFlashcardsNeverPadSource() {
        assertThat((List<?>)service.generate("FLASHCARDS",30,sources).get("items")).hasSize(5);
        for(String mode:List.of("SIGN_MATCHING","ALPHABET_MATCHING")) {
            var items=(List<Map<String,Object>>)service.generate(mode,30,sources).get("items");
            assertThat(items).hasSize(1);
            var item=items.getFirst(); int n=mode.equals("SIGN_MATCHING")?5:2;
            assertThat((List<?>)item.get("left")).hasSize(n); assertThat((List<?>)item.get("right")).hasSize(n); assertThat((List<?>)item.get("correctPairs")).hasSize(n);
        }
    }
    @Test void ambiguousNamesVideosAndImagesCannotBecomeDifferentAnswers() {
        var first=signs.getFirst(); signs.set(1,new Sign(UUID.randomUUID(),first.name().toUpperCase(),UUID.randomUUID(),UUID.randomUUID(),true));
        signs.set(2,new Sign(UUID.randomUUID(),"Different",first.videoAssetId(),UUID.randomUUID(),false));
        var modes=(List<Map<String,Object>>)service.availability(sources).get("modes");
        assertThat(modes.getFirst().get("eligibleSignCount")).isEqualTo(3);
    }
    @Test void missingOrRevokedMediaIsDependencyFailureRatherThanRetiredLesson() {
        when(media.refresh(anyList())).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_REMOVED","revoked media"));
        code("SERVICE_UNAVAILABLE",()->service.generate("QUIZ",10,sources));
        doReturn(List.of()).when(media).refresh(anyList());
        code("SERVICE_UNAVAILABLE",()->service.generate("QUIZ",10,sources));
    }
    static void code(String code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationFailure.class,e->assertThat(e.code()).isEqualTo(code)); }
}
