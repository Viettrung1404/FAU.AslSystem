package com.asl.backend.media;

import com.asl.backend.media.adapter.in.web.*;
import com.asl.backend.media.application.MediaService;
import com.asl.backend.media.application.port.out.MediaUrlProvider;
import com.asl.backend.media.domain.MediaAsset;
import com.asl.backend.platform.adapter.in.web.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaHttpTest {
    static final UUID VIDEO = UUID.fromString("3a0cd01b-5ff3-51af-b6fc-a2f7bcc11313");
    static final UUID IMAGE = UUID.fromString("a7b274aa-f97e-534c-8dff-d7367c8c0506");
    final MediaServiceTest.MemoryAssets repository = new MediaServiceTest.MemoryAssets();
    final Clock clock = Clock.fixed(MediaServiceTest.NOW, ZoneOffset.UTC);
    MediaUrlProvider provider = key -> new MediaUrlProvider.Delivery(URI.create(
            key.equals("hello.mp4") ? "https://mobireactorasl.s3-us-west-2.amazonaws.com/hello.mp4"
                    : "https://mobireactoraslresize.s3-us-west-2.amazonaws.com/thumbnails/hello.png"), null);
    MockMvc mvc;

    @BeforeEach void setUp() {
        repository.assets.put(VIDEO, new MediaAsset(VIDEO, "hello.mp4", "video/mp4", 2280, clock.instant(), null));
        repository.assets.put(IMAGE, new MediaAsset(IMAGE, "hello.png", "image/png", null, clock.instant(), null));
        mvc = mvc(120);
    }

    MockMvc mvc(int limit) {
        return MockMvcBuilders.standaloneSetup(new MediaController(new MediaService(repository, provider, clock)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addFilters(new RequestIdFilter())
                .addInterceptors(new MediaHttpPolicy(clock, limit, Duration.ofMinutes(1), 10000)).build();
    }

    @Test void anonymousRefreshReturnsOnlyContractFieldsAndNoStore() throws Exception {
        var response = postJson("{\"assetIds\":[\"" + VIDEO + "\",\"" + IMAGE + "\"]}")
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].assetId").value(VIDEO.toString()))
                .andExpect(jsonPath("$.items[0].durationMs").value(2280))
                .andExpect(jsonPath("$.items[1].mimeType").value("image/png"))
                .andExpect(jsonPath("$.items[1].durationMs").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].expiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        var json = JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
        assertThat(json.properties()).hasSize(1);
        assertThat(json.get("items").get(0).properties()).hasSize(5);
        capture("200", response);
    }

    @Test void malformedJsonAndUnknownFieldsReturn400() throws Exception {
        for (String body : List.of("{", "{\"assetIds\":[\"" + VIDEO + "\"],\"storageKey\":\"private\"}")) {
            error(body, 400, "BAD_REQUEST");
        }
    }

    @Test void signedProviderExpiryIsSerializedAsContractDateTime() throws Exception {
        provider = key -> new MediaUrlProvider.Delivery(URI.create("https://cdn.example.org/clip.mp4?signature=test"),
                clock.instant().plusSeconds(60));
        mvc = mvc(120);
        var result = postJson("{\"assetIds\":[\"" + VIDEO + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].expiresAt").value("2026-10-21T08:01:00Z")).andReturn();
        capture("200-signed", result);
    }

    @Test void invalidBoundsIdsAndDuplicatesReturn422() throws Exception {
        for (String body : List.of("{}", "{\"assetIds\":null}", "{\"assetIds\":[]}",
                "{\"assetIds\":[null]}", "{\"assetIds\":[\"1-1-1-1-1\"]}",
                "{\"assetIds\":[\"not-a-uuid\"]}",
                "{\"assetIds\":[\"" + VIDEO + "\",\"" + VIDEO.toString().toUpperCase() + "\"]}",
                JsonMapper.builder().build().writeValueAsString(Map.of("assetIds", Collections.nCopies(101, VIDEO))))) {
            error(body, 422, "VALIDATION_FAILED");
        }
    }

    @Test void missingDraftAndRevokedAreAtomicAndCorrelated() throws Exception {
        error("{\"assetIds\":[\"" + VIDEO + "\",\"" + UUID.randomUUID() + "\"]}", 404, "MEDIA_NOT_FOUND");
        repository.assets.put(VIDEO, new MediaAsset(VIDEO, "hello.mp4", "video/mp4", 2280, null, null));
        error("{\"assetIds\":[\"" + VIDEO + "\"]}", 404, "MEDIA_NOT_FOUND");
        repository.assets.put(VIDEO, new MediaAsset(VIDEO, "hello.mp4", "video/mp4", 2280, clock.instant(), clock.instant()));
        error("{\"assetIds\":[\"" + VIDEO + "\"]}", 410, "MEDIA_REMOVED");
    }

    @Test void providerOutageReturnsSanitized503() throws Exception {
        provider = key -> { throw new MediaUrlProvider.Unavailable("secret"); };
        mvc = mvc(120);
        error("{\"assetIds\":[\"" + VIDEO + "\"]}", 503, "SERVICE_UNAVAILABLE");
    }

    @Test void limitsBySocketAddressAndDoesNotTrustForwardedHeader() throws Exception {
        mvc = mvc(1);
        postJson("{\"assetIds\":[\"" + VIDEO + "\"]}").andExpect(status().isOk());
        var result = mvc.perform(post("/api/v1/media/urls").header("X-Forwarded-For", "203.0.113.8")
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetIds\":[\"" + VIDEO + "\"]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        capture("429", result);
    }

    ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/api/v1/media/urls").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    void error(String body, int status, String code) throws Exception {
        var result = postJson(body).andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var response = result.getResponse();
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id")).doesNotContain("secret", "storageKey");
        capture(Integer.toString(status), result);
    }
    static void capture(String name, MvcResult result) throws Exception {
        Path folder = Path.of("target/media-http");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name + ".json"), result.getResponse().getContentAsString());
    }
}
