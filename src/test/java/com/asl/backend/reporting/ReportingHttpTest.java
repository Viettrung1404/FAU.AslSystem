package com.asl.backend.reporting;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.reporting.adapter.in.web.ReportingController;
import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.platform.adapter.in.web.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ReportingHttpTest {
    final ReportingApi api=mock(ReportingApi.class); final IdentityApi identity=mock(IdentityApi.class); MockMvc mvc;
    final String body="{\"clientReportId\":\"11111111-1111-4111-8111-111111111111\",\"targetType\":\"SIGN\",\"targetId\":\"22222222-2222-4222-8222-222222222222\",\"reason\":\"OTHER\",\"message\":\"video issue\"}";
    @BeforeEach void setup() { mvc=build(10); when(api.submit(any(),any())).thenReturn(new ReportingApi.Receipt(UUID.randomUUID(),"RECEIVED",Instant.parse("2026-11-07T00:00:00Z"))); }
    MockMvc build(int requests) { return MockMvcBuilders.standaloneSetup(new ReportingController(api,identity,requests,Duration.ofMinutes(1),100)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build(); }
    @Test void guestGetsCreatedReceiptAndNoStore() throws Exception {
        var response=mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value("RECEIVED")).andReturn(); capture(201,response);
        verify(identity).optionalAccount(null); verify(api).submit(any(),isNull());
    }
    @Test void invalidOptionalTokenIs401AndNeverBecomesGuest() throws Exception {
        when(identity.optionalAccount("Bearer invalid")).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Invalid authentication"));
        var response=mvc.perform(post("/api/v1/reports").header("Authorization","Bearer invalid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized()).andReturn(); capture(401,response); verifyNoInteractions(api);
    }
    @Test void validTokenUsesInternalOwner() throws Exception {
        var owner=UUID.randomUUID(); when(identity.optionalAccount("Bearer valid")).thenReturn(new IdentityApi.Account(owner,"a@example.com","A"));
        mvc.perform(post("/api/v1/reports").header("Authorization","Bearer valid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()); verify(api).submit(any(),eq(owner));
    }
    @Test void limiterProvidesRetryAfterAndQueryIsRejected() throws Exception {
        mvc=build(1); mvc.perform(post("/api/v1/reports?extra=true").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        var response=mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andReturn(); capture(429,response); verifyNoInteractions(api);
    }
    @Test void malformedUuidAndApplicationFailuresUseContractErrors() throws Exception {
        capture(400,mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body.replace("11111111-1111-4111-8111-111111111111","bad"))).andExpect(status().isBadRequest()).andReturn());
        var kinds=List.of(ApplicationFailure.Kind.INVALID_INPUT,ApplicationFailure.Kind.NOT_FOUND,ApplicationFailure.Kind.CONFLICT,ApplicationFailure.Kind.UNAVAILABLE);
        var codes=List.of("VALIDATION_FAILED","NOT_FOUND","REPORT_ID_REUSED","SERVICE_UNAVAILABLE"); var statuses=List.of(422,404,409,503);
        for(int i=0;i<kinds.size();i++) { doThrow(new ApplicationFailure(kinds.get(i),codes.get(i),"Request failed")).when(api).submit(any(),any());
            var response=mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(statuses.get(i))).andExpect(jsonPath("$.error.code").value(codes.get(i))).andReturn(); capture(statuses.get(i),response); }
    }
    @Test void explicitNullOptionalFieldsAreRejectedWhileOmittedFieldsAreAccepted() throws Exception {
        for(String field:List.of("lessonVersionId","practiceContext"))
            mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body.substring(0,body.length()-1)+",\""+field+"\":null}")).andExpect(status().isBadRequest());
        verifyNoInteractions(api);
    }
    static void capture(int status,MvcResult result) throws Exception { var path=Path.of("target/reporting-http"); Files.createDirectories(path); Files.writeString(path.resolve("support_report-"+status+"-http.json"),result.getResponse().getContentAsString()); }
}
