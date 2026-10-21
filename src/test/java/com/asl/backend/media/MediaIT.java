package com.asl.backend.media;

import com.asl.backend.media.application.api.MediaApi;
import com.asl.backend.media.application.port.out.AssetRepository;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "MEDIA_PUBLIC_BASE_URL=https://mobireactorasl.s3-us-west-2.amazonaws.com/")
class MediaIT {
    @Autowired MediaApi api;
    @Autowired AssetRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;

    @Test void persistenceMapsMetadataAndNullsFromExistingSchema() {
        UUID id = insert("image/png", null, "mapping/" + UUID.randomUUID() + ".png");
        var result = repository.findAll(List.of(id, UUID.randomUUID()));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().mimeType()).isEqualTo("image/png");
        assertThat(result.getFirst().durationMs()).isNull();
        assertThat(result.getFirst().publishedAt()).isNull();
        assertThat(result.getFirst().revokedAt()).isNull();
    }

    @Test void publicationAndRevocationArePersistentIdempotentAndTerminal() {
        UUID id = insert("video/mp4", 2280, "states/" + UUID.randomUUID() + ".mp4");
        assertCode("MEDIA_NOT_FOUND", () -> api.refresh(List.of(id)));
        api.publish(id);
        Instant first = repository.findAll(List.of(id)).getFirst().publishedAt();
        api.publish(id);
        assertThat(repository.findAll(List.of(id)).getFirst().publishedAt()).isEqualTo(first);
        assertThat(api.refresh(List.of(id))).hasSize(1);
        api.revoke(id);
        Instant revoked = repository.findAll(List.of(id)).getFirst().revokedAt();
        api.revoke(id);
        assertThat(repository.findAll(List.of(id)).getFirst().revokedAt()).isEqualTo(revoked);
        assertCode("MEDIA_REMOVED", () -> api.refresh(List.of(id)));
        assertCode("MEDIA_REMOVED", () -> api.publish(id));
    }

    @Test void publicationParticipatesInCallerTransactionRollback() {
        UUID id = insert("video/mp4", 1, "rollback/" + UUID.randomUUID() + ".mp4");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            api.publish(id);
            assertThat(api.refresh(List.of(id))).hasSize(1);
            status.setRollbackOnly();
        });
        assertCode("MEDIA_NOT_FOUND", () -> api.refresh(List.of(id)));
    }

    @Test void concurrentPublishCannotRestoreARevokedAsset() throws Exception {
        UUID id = insert("video/mp4", 1, "race/" + UUID.randomUUID() + ".mp4");
        api.publish(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CountDownLatch(1);
            var publishing = executor.submit(() -> {
                barrier.await();
                try { api.publish(id); } catch (ApplicationFailure failure) {
                    assertThat(failure.code()).isEqualTo("MEDIA_REMOVED");
                }
                return true;
            });
            var revoking = executor.submit(() -> { barrier.await(); api.revoke(id); return true; });
            barrier.countDown();
            assertThat(publishing.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(revoking.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertCode("MEDIA_REMOVED", () -> api.refresh(List.of(id)));
    }

    @Test void realHttpUsesPersistedPublicationStateAndContractEnvelope() throws Exception {
        UUID id = insert("video/mp4", 2280, "http/" + UUID.randomUUID() + ".mp4");
        assertThat(post(id).statusCode()).isEqualTo(404);
        api.publish(id);
        var response = post(id);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("\"durationMs\":2280", "\"expiresAt\":null");
        Files.createDirectories(Path.of("target/media-http"));
        Files.writeString(Path.of("target/media-http/200-integration.json"), response.body());
        api.revoke(id);
        var removed = post(id);
        assertThat(removed.statusCode()).isEqualTo(410);
        assertThat(removed.body()).contains("MEDIA_REMOVED", removed.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test void issuedPublicUrlCanDownloadActualFixtureMedia() throws Exception {
        // Source: api-contract/examples/mock-media.json / Pocket Sign hello, development use only.
        var existing = jdbc.queryForList("select id from asl.media_assets where storage_key='hello.mp4'", UUID.class);
        UUID id = existing.isEmpty() ? insert("video/mp4", 2280, "hello.mp4") : existing.getFirst();
        api.publish(id);
        var response = post(id);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JsonMapper.builder().build().readTree(response.body());
        URI url = URI.create(json.get("items").get(0).get("url").asString());
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).header("Range", "bytes=0-1023").GET().build();
        var media = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(media.statusCode()).isEqualTo(206);
        assertThat(media.headers().firstValue("Content-Type").orElseThrow()).startsWith("video/mp4");
        assertThat(media.body()).hasSize(1024);
        assertThat(new String(media.body(), 4, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("ftyp");
        // Retain this published fixture for repeat runs: V2 forbids deleting/restoring published assets.
    }

    private UUID insert(String mime, Integer duration, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asl.media_assets(id,storage_key,mime_type,duration_ms,sha256_checksum,rights_reference)
                VALUES (?,?,?,?,?,?)
                """, id, key, mime, duration,
                key.equals("hello.mp4") ? "1a4ae6ade490658c0e51f4c86b1fd18e66795d3ad110dfc5b4976dca0c0df3cd" : "0".repeat(64),
                key.equals("hello.mp4") ? "Development link: https://www.pocketsign.org/asl/hello; not a production license"
                        : "Synthetic test metadata; no corresponding production asset");
        return id;
    }

    private HttpResponse<String> post(UUID id) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/media/urls"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("{\"assetIds\":[\"" + id + "\"]}")).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationFailure.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
