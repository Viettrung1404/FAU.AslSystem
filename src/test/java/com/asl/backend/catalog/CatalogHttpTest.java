package com.asl.backend.catalog;

import com.asl.backend.catalog.adapter.in.web.*;
import com.asl.backend.catalog.application.CatalogService;
import com.asl.backend.catalog.adapter.out.cursor.HmacCatalogCursor;
import com.asl.backend.platform.adapter.in.web.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogHttpTest {
    CatalogServiceTest fixture;
    MockMvc mvc;
    UUID firstUnit;
    @BeforeEach void setUp() {
        fixture = new CatalogServiceTest();
        UUID previous = UUID.randomUUID();
        for (String type : List.of("LESSON", "ALPHABET", "REVIEW", "CONVERSATION")) {
            var unit = fixture.unit(fixture.store.units.size());
            var lesson = fixture.lesson(unit.id(), 0, type, List.of(previous)); previous = lesson.id();
        }
        firstUnit = fixture.store.units.getFirst().id();
        mvc = mvc(false, 120, "https://support.example.org/asl");
    }
    MockMvc mvc(boolean enabled, int budget, String support) {
        var service = new CatalogService(fixture.store, (ids, at) -> fixture.summaries, new HmacCatalogCursor(CatalogCursorTest.KEY),
                enabled, support, Clock.fixed(CatalogServiceTest.NOW, ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new CatalogController(service)).setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .addFilters(new RequestIdFilter()).addInterceptors(new CatalogHttpPolicy(
                        Clock.fixed(CatalogServiceTest.NOW, ZoneOffset.UTC), budget, Duration.ofMinutes(1), 10000)).build();
    }
    @Test void configHasAllContractFieldsAndUnlimitedLearning() throws Exception {
        var result = mvc.perform(get("/api/v1/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("0.3.0"))
                .andExpect(jsonPath("$.dailyLessonLimit").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.practiceModes.length()").value(4))
                .andExpect(jsonPath("$.practiceMinimumDistinctLessons").value(3))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        capture("catalog_config", 200, result);
    }
    @Test void homeAndUnitsShareCursorAndAnonymousRepresentation() throws Exception {
        var home = mvc.perform(get("/api/v1/home").param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.units.items.length()").value(2)).andReturn();
        capture("catalog_home", 200, home);
        var tree = JsonMapper.builder().build().readTree(home.getResponse().getContentAsString());
        String cursor = tree.get("units").get("nextCursor").asString();
        var last = mvc.perform(get("/api/v1/units").param("cursor", cursor).param("limit", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue())).andReturn();
        capture("catalog_units", 200, last);
        var guest = mvc.perform(get("/api/v1/units")).andExpect(status().isOk()).andReturn();
        var account = mvc.perform(get("/api/v1/units").header("Authorization", "Bearer ignored-on-public-catalog"))
                .andExpect(status().isOk()).andReturn();
        assertThat(account.getResponse().getContentAsString()).isEqualTo(guest.getResponse().getContentAsString());
    }
    @Test void everyEndpointUsesTheSamePrerequisitePolicyAndChangingModeRejectsOldCursor() throws Exception {
        var first = mvc.perform(get("/api/v1/units").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lessons[0].prerequisiteLessonIds").isEmpty()).andReturn();
        String oldCursor = JsonMapper.builder().build().readTree(first.getResponse().getContentAsString()).get("nextCursor").asString();
        for (var unit : fixture.store.units) mvc.perform(get("/api/v1/units/" + unit.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].prerequisiteLessonIds").isEmpty()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/home")).andExpect(status().isOk()).andExpect(jsonPath("$.units.items[0].lessons[0].prerequisiteLessonIds").isEmpty());
        mvc = mvc(true, 120, "https://support.example.org/asl");
        var detail = mvc.perform(get("/api/v1/units/" + firstUnit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].prerequisiteLessonIds.length()").value(1)).andReturn();
        capture("catalog_unit", 200, detail);
        error("catalog_units", "/api/v1/units?cursor=" + oldCursor, 400, "BAD_REQUEST");
    }
    @Test void malformedQueriesAndUnknownUnitHaveContractErrors() throws Exception {
        for (String query : List.of("limit=0", "limit=101", "limit=abc", "limit=1.5", "limit=", "cursor=", "cursor=bad", "sort=name", "limit=1&limit=2"))
            error("catalog_units", "/api/v1/units?" + query, 400, "BAD_REQUEST");
        error("catalog_home", "/api/v1/home?limit=-1", 400, "BAD_REQUEST");
        error("catalog_config", "/api/v1/config?filter=secret", 400, "BAD_REQUEST");
        error("catalog_unit", "/api/v1/units/1-1-1-1-1", 400, "BAD_REQUEST");
        error("catalog_unit", "/api/v1/units/" + UUID.randomUUID(), 404, "NOT_FOUND");
    }
    @Test void missingDeploymentConfigReturns503InsteadOfPlaceholderSuccess() throws Exception {
        mvc = mvc(true, 120, "");
        error("catalog_config", "/api/v1/config", 503, "SERVICE_UNAVAILABLE");
        mvc.perform(get("/api/v1/units")).andExpect(status().isOk());
    }
    @Test void rateLimitIsSharedAcrossEndpointsAndIgnoresForwardedHeader() throws Exception {
        mvc = mvc(false, 1, "https://support.example.org/asl");
        mvc.perform(get("/api/v1/config")).andExpect(status().isOk());
        for (var entry : Map.of("catalog_config", "/api/v1/config", "catalog_home", "/api/v1/home",
                "catalog_units", "/api/v1/units", "catalog_unit", "/api/v1/units/" + firstUnit).entrySet()) {
            var result = mvc.perform(get(entry.getValue()).header("X-Forwarded-For", "203.0.113.22"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.error.code").value("RATE_LIMITED")).andReturn();
            capture(entry.getKey(), 429, result);
        }
    }
    void error(String operation, String url, int status, String code) throws Exception {
        var result = mvc.perform(get(url)).andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(result.getResponse().getHeader("X-Request-Id"));
        capture(operation, status, result);
    }
    static void capture(String operation, int status, MvcResult result) throws Exception {
        Path path = Path.of("target/catalog-http"); Files.createDirectories(path);
        Files.writeString(path.resolve(operation + "-" + status + ".json"), result.getResponse().getContentAsString());
    }
}
