package com.asl.backend.identity;

import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.identity.application.IdentityService;
import com.asl.backend.identity.application.port.out.*;
import com.asl.backend.identity.adapter.in.web.*;
import com.asl.backend.platform.adapter.in.web.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdentityHttpTest {
    final IdentityApi identity=mock(IdentityApi.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new IdentityController(identity)).setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
            .addFilters(new RequestIdFilter()).addInterceptors(new IdentityHttpPolicy(Clock.systemUTC(),60,Duration.ofMinutes(1),100)).build();
    }
    @Test void profileIncludesNullEmailAndUsesPrivateNoStore() throws Exception {
        var account=new IdentityApi.Account(UUID.randomUUID(),null,null);
        when(identity.requireAccount("Bearer token")).thenReturn(account);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer token")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(jsonPath("$.id").value(account.id().toString()))
            .andExpect(content().json("{\"id\":\""+account.id()+"\",\"email\":null,\"name\":null}"));
    }
    @Test void missingNullOrUnknownPatchFieldsNeverSelectAccount() throws Exception {
        for(String body:List.of("{}","{\"name\":3}","{\"name\":null,\"email\":\"x@y.com\"}","{\"userId\":\"other\"}"))
            mvc.perform(patch("/api/v1/me").contentType("application/json").content(body)).andExpect(status().isUnprocessableContent());
        verifyNoInteractions(identity);
    }
    @Test void bootstrapRejectsUnknownIdentityFieldsAndQueries() throws Exception {
        mvc.perform(post("/api/v1/auth/bootstrap").contentType("application/json")
            .content("{\"timeZone\":\"UTC\",\"acceptedDocumentIds\":[],\"firebaseUid\":\"other\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/legal-documents?userId=other")).andExpect(status().isBadRequest());
        verifyNoInteractions(identity);
    }
    @Test void firebaseUnavailableDoesNotImpactPublicLegalDocuments() throws Exception {
        var repository=mock(AccountRepository.class);
        when(repository.legalDocuments()).thenReturn(List.of());
        var service=new IdentityService(new com.asl.backend.identity.adapter.out.firebase.FirebaseIdTokenVerifier(""),repository);
        var controller=new IdentityController(service);
        var local=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        local.perform(get("/api/v1/legal-documents")).andExpect(status().isOk());
        local.perform(get("/api/v1/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        local.perform(get("/api/v1/me").header("Authorization","Bearer plausible.signed.jwt")).andExpect(status().isServiceUnavailable());
    }
}
