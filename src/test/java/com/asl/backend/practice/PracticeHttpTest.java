package com.asl.backend.practice;
import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.practice.adapter.in.web.*;
import com.asl.backend.platform.adapter.in.web.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PracticeHttpTest {
    final PracticeApi api=mock(PracticeApi.class); MockMvc mvc;
    @BeforeEach void setup() { mvc=build(120); }
    MockMvc build(int budget) { return MockMvcBuilders.standaloneSetup(new PracticeController(api)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter())
        .addInterceptors(new PracticeHttpPolicy(Clock.systemUTC(),budget,Duration.ofMinutes(1),100)).build(); }
    @Test void missingFieldsLegacyIdsNullPairsAndBoundsAreRejected() throws Exception {
        for(String body:List.of("{}","{\"completedLessons\":null}","{\"completedLessons\":[null]}","{\"completedLessons\":[{}]}"))
            mvc.perform(post("/api/v1/practice/availability").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity());
        for(String body:List.of("{\"mode\":\"QUIZ\",\"completedLessons\":[],\"itemCount\":0}","{\"mode\":\"QUIZ\",\"completedLessons\":[],\"itemCount\":31}"))
            mvc.perform(post("/api/v1/practice/sets").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(api);
    }
    @Test void errorsConformForBothOperations() throws Exception {
        for(String operation:List.of("practice_availability","practice_generate")) {
            String path=operation.endsWith("availability")?"availability":"sets",body=path.equals("sets")?"{\"mode\":\"QUIZ\",\"completedLessons\":[]}":"{\"completedLessons\":[]}";
            for(var failure:List.of(new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","bad"),
                    new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","invalid"),new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_VERSION_RETIRED","retired"),
                    new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","limited"),new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","unavailable"))) {
                reset(api); doThrow(failure).when(api).availability(anyList()); doThrow(failure).when(api).generate(anyString(),any(),anyList());
                int status=switch(failure.kind()){case BAD_REQUEST->400;case INVALID_INPUT->422;case REMOVED->410;case RATE_LIMITED->429;default->503;};
                capture(operation,status,mvc.perform(post("/api/v1/practice/"+path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store")).andReturn());
            }
        }
        for(String code:List.of("NEED_MORE_LESSONS","INSUFFICIENT_CONTENT")) {
            reset(api); doThrow(new ApplicationFailure(ApplicationFailure.Kind.CONFLICT,code,"not ready")).when(api).generate(anyString(),any(),anyList());
            capture("practice_generate",409,mvc.perform(post("/api/v1/practice/sets").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"QUIZ\",\"completedLessons\":[]}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(code)).andReturn(),code);
        }
    }
    @Test void actualLimiterAndUnknownQueries() throws Exception {
        mvc=build(1); mvc.perform(post("/api/v1/practice/availability?extra=true")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/practice/availability")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    static void capture(String op,int status,MvcResult response,String... suffix) throws Exception {
        Path folder=Path.of("target/practice-http"); Files.createDirectories(folder); Files.writeString(folder.resolve(op+"-"+status+"-http"+String.join("",suffix)+".json"),response.getResponse().getContentAsString());
    }
}
