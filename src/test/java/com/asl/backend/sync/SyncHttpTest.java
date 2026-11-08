package com.asl.backend.sync;

import com.asl.backend.sync.application.api.*;
import com.asl.backend.sync.adapter.in.web.*;
import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import com.asl.backend.platform.adapter.in.web.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SyncHttpTest {
    final SyncApi api=mock(SyncApi.class);
    final IdentityApi identity=mock(IdentityApi.class);
    final UUID owner=UUID.randomUUID();
    MockMvc mvc;
    @BeforeEach void setup() {
        when(identity.requireAccount("Bearer verified-test-token")).thenReturn(new IdentityApi.Account(owner,null,"Synthetic test learner"));
        when(identity.requireAccount(isNull())).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Authentication required"));
        when(identity.requireAccount("Bearer invalid")).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Invalid token"));
        mvc=MockMvcBuilders.standaloneSetup(new SyncController(api,identity)).setControllerAdvice(new SyncExceptionHandler(),new ApiExceptionHandler())
            .addFilters(new RequestIdFilter()).addInterceptors(new SyncHttpPolicy()).build();
    }
    @Test void allFourOperationsUseVerifiedAccountAndSerializeContractResponses() throws Exception {
        var page=new LinkedHashMap<String,Object>();page.put("items",List.of());page.put("nextCursor",null);page.put("syncCursor","signed-test-watermark");
        when(api.snapshot(owner,null,20)).thenReturn(page);when(api.changes(owner,"signed-test-watermark",null,20)).thenReturn(page);
        when(api.stats(owner)).thenReturn(Map.of("totalPoints",0,"completedLessonIds",List.of(),"learnedSignIds",List.of(),"currentStreak",0,"recordStreak",0,"activityDates",List.of()));
        String id=UUID.randomUUID().toString();
        var prefs=new LinkedHashMap<String,Object>();prefs.put("hapticsEnabled",true);prefs.put("dailyReminderEnabled",false);prefs.put("dailyReminderLocalTime",null);prefs.put("learningGoalDays","7");prefs.put("timeZone","UTC");
        var change=Map.of("kind","PREFERENCES","entityKey","preferences","revision",1,"data",prefs);
        when(api.push(eq(owner),anyMap())).thenReturn(Map.of("results",List.of(Map.of("mutationId",id,"outcome","APPLIED","change",change))));
        capture("sync_snapshot-200",mvc.perform(get("/api/v1/sync/snapshot").header("Authorization","Bearer verified-test-token")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue())).andReturn());
        capture("sync_changes-200",mvc.perform(get("/api/v1/sync/changes?since=signed-test-watermark").header("Authorization","Bearer verified-test-token")).andExpect(status().isOk()).andReturn());
        capture("account_stats-200",mvc.perform(get("/api/v1/me/stats").header("Authorization","Bearer verified-test-token")).andExpect(status().isOk()).andReturn());
        var request=Map.of("mutations",List.of(Map.of("kind","PREFERENCES","mutationId",id,"entityKey","preferences","baseRevision",0,"data",prefs)));
        capture("sync_push-200",mvc.perform(post("/api/v1/sync/mutations").header("Authorization","Bearer verified-test-token").contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request))).andExpect(status().isOk()).andExpect(jsonPath("$.results[0].change.revision").value(1)).andReturn());
        verify(api).snapshot(owner,null,20);verify(api).changes(owner,"signed-test-watermark",null,20);verify(api).stats(owner);verify(api).push(eq(owner),anyMap());
    }
    @Test void absentOrInvalidCredentialsNeverReachSyncUseCase() throws Exception {
        capture("sync_snapshot-401",mvc.perform(get("/api/v1/sync/snapshot")).andExpect(status().isUnauthorized()).andReturn());
        mvc.perform(get("/api/v1/me/stats").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sync/snapshot").header("Authorization","Bearer verified-test-token","Bearer invalid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(api);
    }
    @Test void unknownRepeatedAndMalformedQueriesNeverReachSyncUseCase() throws Exception {
        for(String path:List.of("/api/v1/sync/snapshot?owner="+UUID.randomUUID(),"/api/v1/sync/snapshot?limit=1&limit=2","/api/v1/sync/snapshot?limit=no","/api/v1/sync/changes","/api/v1/me/stats?extra=true"))
            mvc.perform(get(path).header("Authorization","Bearer verified-test-token")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(api);
    }
    @Test void conflictsExposeRevisionAndFieldAndShareRequestId() throws Exception {
        when(api.push(eq(owner),anyMap())).thenThrow(new SyncFailure(ApplicationFailure.Kind.CONFLICT,"ENTITY_REVISION_CONFLICT","Entity changed","/mutations/0/baseRevision",4L));
        var response=mvc.perform(post("/api/v1/sync/mutations").header("Authorization","Bearer verified-test-token").contentType("application/json").content("{\"mutations\":[]}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.currentRevision").value(4)).andExpect(jsonPath("$.error.fields[0].path").value("/mutations/0/baseRevision")).andReturn();
        capture("sync_push-409",response);
        org.assertj.core.api.Assertions.assertThat(response.getResponse().getContentAsString()).contains(response.getResponse().getHeader("X-Request-Id"));
    }
    @Test void cursorExpiryAndBoundsPreserveContractErrors() throws Exception {
        when(api.snapshot(owner,"expired",20)).thenThrow(new SyncFailure(ApplicationFailure.Kind.REMOVED,"SYNC_CURSOR_EXPIRED","Fetch new snapshot","/cursor",null));
        capture("sync_snapshot-410",mvc.perform(get("/api/v1/sync/snapshot?cursor=expired").header("Authorization","Bearer verified-test-token")).andExpect(status().isGone()).andReturn());
        when(api.snapshot(owner,null,101)).thenThrow(new SyncFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Limit must be at most 100","/limit",null));
        capture("sync_snapshot-422",mvc.perform(get("/api/v1/sync/snapshot?limit=101").header("Authorization","Bearer verified-test-token")).andExpect(status().isUnprocessableEntity()).andReturn());
    }
    private static void capture(String name,MvcResult result) throws Exception { Path dir=Path.of("target/sync-http");Files.createDirectories(dir);Files.writeString(dir.resolve(name+"-http.json"),result.getResponse().getContentAsString()); }
}
