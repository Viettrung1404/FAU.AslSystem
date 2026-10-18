package com.asl.backend.platform.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpFoundationTest {
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addFilters(new RequestIdFilter()).build();
    }

    @Test void malformedJsonReturns400AndCorrelatedUuid() throws Exception {
        var result = mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andReturn();
        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(java.util.UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(result.getResponse().getContentAsString()).contains(id);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test void unknownPropertiesAreRejected() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test void invalidPayloadReturns422WithFieldPath() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields[0].path").value("/name"))
                .andExpect(jsonPath("$.error.currentRevision").doesNotExist());
    }

    @Test void requestIdsAreServerOwnedAndUnique() throws Exception {
        String first = mvc.perform(post("/probe").header(RequestIdFilter.HEADER, "untrusted")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        String second = mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\"}"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(first).isNotEqualTo("untrusted").isNotEqualTo(second);
    }

    @Test void unsupportedMethodRetains405InsteadOf500() throws Exception {
        mvc.perform(get("/probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @RestController static class ProbeController {
        @PostMapping("/probe") Payload probe(@Valid @RequestBody Payload payload) { return payload; }
    }
    record Payload(@NotBlank String name) { }
}
