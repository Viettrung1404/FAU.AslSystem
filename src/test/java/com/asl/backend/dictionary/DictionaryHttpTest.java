package com.asl.backend.dictionary;
import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.dictionary.adapter.in.web.*;
import com.asl.backend.platform.adapter.in.web.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class DictionaryHttpTest {
    final DictionaryApi api=mock(DictionaryApi.class);
    MockMvc mvc;
    final String id=UUID.randomUUID().toString();
    @BeforeEach void setup() { mvc=build(120); }
    MockMvc build(int budget) { return MockMvcBuilders.standaloneSetup(new DictionaryController(api)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter())
        .addInterceptors(new DictionaryHttpPolicy(Clock.systemUTC(),budget,Duration.ofMinutes(1),100)).build(); }
    @Test void invalidQueriesAndIdsNeverReachApplication() throws Exception {
        for(String path:List.of("signs?limit=0","signs?limit=101","signs?limit=1&limit=2","signs?extra=true","signs/bad","collections/bad","search?q=x&type=ALL&type=SIGN","collections/"+id+"/signs?extra=true"))
            mvc.perform(get("/api/v1/"+path)).andExpect(status().isBadRequest()).andExpect(header().exists("Cache-Control"));
        verifyNoInteractions(api);
    }
    @Test void everyOperationUsesErrorEnvelopeAndMembershipIsPrivate() throws Exception {
        var paths=Map.of("dictionary_signs","signs","dictionary_sign","signs/"+id,"dictionary_collections","collections","dictionary_collection","collections/"+id,"dictionary_collectionSigns","collections/"+id+"/signs","dictionary_search","search?q=hello");
        for(var entry:paths.entrySet()) for(var failure:List.of(
                new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","bad"),new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","invalid"),
                new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","limited"),new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","unavailable"))) {
            reset(api); fail(failure);
            int status=switch(failure.kind()) {case BAD_REQUEST->400;case INVALID_INPUT->422;case RATE_LIMITED->429;default->503;};
            capture(entry.getKey(),status,mvc.perform(get("/api/v1/"+entry.getValue())).andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(failure.code())).andReturn());
        }
    }
    @Test void membershipAuthAndMissingDetailErrors() throws Exception {
        for(int status:List.of(401,403,404)) {
            reset(api); fail(new ApplicationFailure(status==401?ApplicationFailure.Kind.UNAUTHENTICATED:status==403?ApplicationFailure.Kind.FORBIDDEN:ApplicationFailure.Kind.NOT_FOUND,status==401?"UNAUTHORIZED":status==403?"PREMIUM_REQUIRED":"NOT_FOUND","denied"));
            capture("dictionary_collectionSigns",status,mvc.perform(get("/api/v1/collections/"+id+"/signs")).andExpect(status().is(status)).andExpect(header().string("Cache-Control","private, no-store")).andReturn());
        }
        for(String kind:List.of("sign","collection")) {
            reset(api); fail(new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND,"NOT_FOUND","missing"));
            capture("dictionary_"+kind,404,mvc.perform(get("/api/v1/"+(kind.equals("sign")?"signs/":"collections/")+id)).andExpect(status().isNotFound()).andReturn());
        }
        reset(api); fail(new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_REMOVED","removed"));
        capture("dictionary_sign",410,mvc.perform(get("/api/v1/signs/"+id)).andExpect(status().isGone()).andReturn());
    }
    @Test void actualLimiterAddsRetryAfter() throws Exception {
        mvc=build(1); mvc.perform(get("/api/v1/signs/bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/signs")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    void fail(ApplicationFailure failure) {
        doThrow(failure).when(api).page(anyString(),any(),any(),anyString(),any(),anyInt(),any());
        doThrow(failure).when(api).sign(any()); doThrow(failure).when(api).collection(any());
    }
    static void capture(String op,int status,MvcResult response) throws Exception {
        Path folder=Path.of("target/dictionary-http"); Files.createDirectories(folder); Files.writeString(folder.resolve(op+"-"+status+"-http.json"),response.getResponse().getContentAsString());
    }
}
