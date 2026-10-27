package com.asl.backend.learning;

import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.learning.adapter.in.web.*;
import com.asl.backend.platform.adapter.in.web.*;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningHttpTest {
    LearningApi api = mock(LearningApi.class);
    MockMvc mvc;
    String id = LearningFixtures.version(0).lessonId().toString();
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new LearningController(api)).setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter()).addInterceptors(new LearningHttpPolicy(Clock.systemUTC(), 120, Duration.ofMinutes(1), 100)).build();
    }
    @Test void publicResponsePreservesNullsAndNoStore() throws Exception {
        when(api.content(any(), isNull())).thenReturn(LearningFixtures.CONTENT.getFirst());
        mvc.perform(get("/api/v1/lessons/"+id)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.exercises.length()").value(13)).andExpect(jsonPath("$.exercises[9].answerKey").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test void malformedIdsUnknownOrRepeatedQueriesAreRejectedBeforeUseCase() throws Exception {
        for (String path : List.of("bad", id+"?versionId=bad", id+"?versionId=", id+"?versionId="+id+"&versionId="+id, id+"?unexpected=true"))
            mvc.perform(get("/api/v1/lessons/"+path)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(api);
    }
    @Test void limiterReturnsRetryAfterAndNoStore() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new LearningController(api)).setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter()).addInterceptors(new LearningHttpPolicy(Clock.systemUTC(), 1, Duration.ofMinutes(1), 1)).build();
        mvc.perform(get("/api/v1/lessons/bad")).andExpect(status().isBadRequest());
        capture(429, mvc.perform(get("/api/v1/lessons/"+id)).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andReturn());
    }
    @Test void retiredAndUnavailableResponsesMatchContract() throws Exception {
        for (var kind : List.of(ApplicationFailure.Kind.REMOVED, ApplicationFailure.Kind.UNAVAILABLE)) {
            int status = kind == ApplicationFailure.Kind.REMOVED ? 410 : 503;
            String code = status == 410 ? "CONTENT_VERSION_RETIRED" : "SERVICE_UNAVAILABLE";
            doThrow(new ApplicationFailure(kind, code, "Content unavailable")).when(api).content(any(), isNull());
            capture(status, mvc.perform(get("/api/v1/lessons/"+id)).andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.error.code").value(code)).andReturn());
        }
    }
    private void capture(int status, MvcResult response) throws Exception {
        Path folder = Path.of("target/learning-http"); Files.createDirectories(folder);
        Files.writeString(folder.resolve("lesson_content-"+status+"-http.json"), response.getResponse().getContentAsString());
    }
}
