package com.asl.backend.media;

import com.asl.backend.media.application.MediaService;
import com.asl.backend.media.application.port.out.AssetRepository;
import com.asl.backend.media.application.port.out.MediaUrlProvider;
import com.asl.backend.media.domain.MediaAsset;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MediaServiceTest {
    static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    final MemoryAssets repository = new MemoryAssets();
    final AtomicInteger deliveries = new AtomicInteger();
    MediaUrlProvider provider = key -> {
        deliveries.incrementAndGet();
        return new MediaUrlProvider.Delivery(URI.create("https://cdn.example.org/" + key), null);
    };
    MediaService service() { return new MediaService(repository, provider, Clock.fixed(NOW, ZoneOffset.UTC)); }

    @Test void returnsPublishedMetadataInRequestOrderWithoutMembership() {
        var video = asset("video/mp4", 2280, NOW, null);
        var image = asset("image/png", null, NOW.minusSeconds(1), null);
        var result = service().refresh(List.of(image.id(), video.id()));
        assertThat(result).extracting(item -> item.assetId()).containsExactly(image.id(), video.id());
        assertThat(result.getFirst().durationMs()).isNull();
        assertThat(result.getLast().durationMs()).isEqualTo(2280);
        assertThat(result.getLast().mimeType()).isEqualTo("video/mp4");
        assertThat(result.getLast().expiresAt()).isNull();
        assertThat(result.getLast().url().getScheme()).isEqualTo("https");
    }

    @Test void acceptsExactlyOneHundredAssets() {
        var ids = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> asset("image/webp", null, NOW, null).id()).toList();
        assertThat(service().refresh(ids)).hasSize(100);
    }

    @Test void rejectsEmptyOversizedDuplicateAndNullIdsBeforeLoading() {
        UUID id = UUID.randomUUID();
        for (List<UUID> invalid : Arrays.<List<UUID>>asList(null, List.of(), List.of(id, id),
                Collections.nCopies(101, id), Arrays.asList(id, null))) {
            assertThatThrownBy(() -> service().refresh(invalid)).isInstanceOf(ApplicationFailure.class)
                    .hasMessage("Invalid assetIds");
        }
        assertThat(repository.reads).isZero();
    }

    @Test void validatesWholeBatchBeforeIssuingAnyUrl() {
        var available = asset("video/mp4", 1, NOW, null);
        assertCode("MEDIA_NOT_FOUND", () -> service().refresh(List.of(available.id(), UUID.randomUUID())));
        assertThat(deliveries).hasValue(0);
    }

    @Test void hidesDraftAndFuturePublication() {
        for (Instant published : Arrays.asList(null, NOW.plusSeconds(1))) {
            var draft = asset("video/mp4", 1, published, null);
            assertCode("MEDIA_NOT_FOUND", () -> service().refresh(List.of(draft.id())));
        }
    }

    @Test void revokedIsGoneButMissingTakesPrecedenceRegardlessOfOrder() {
        var revoked = asset("image/png", null, NOW.minusSeconds(1), NOW);
        assertCode("MEDIA_REMOVED", () -> service().refresh(List.of(revoked.id())));
        UUID absent = UUID.randomUUID();
        assertCode("MEDIA_NOT_FOUND", () -> service().refresh(List.of(revoked.id(), absent)));
        assertCode("MEDIA_NOT_FOUND", () -> service().refresh(List.of(absent, revoked.id())));
        assertThat(deliveries).hasValue(0);
    }

    @Test void providerFailureIsUnavailableWithoutLeakingDetails() {
        var asset = asset("video/mp4", 1, NOW, null);
        provider = key -> { throw new MediaUrlProvider.Unavailable("secret storage credential"); };
        assertCode("SERVICE_UNAVAILABLE", () -> service().refresh(List.of(asset.id())));
    }

    @Test void signedExpiryIsPreservedAndExpiredDeliveryRejected() {
        var asset = asset("video/mp4", 1, NOW, null);
        provider = key -> new MediaUrlProvider.Delivery(URI.create("https://cdn.example.org/video?token=123"), NOW.plusSeconds(60));
        assertThat(service().refresh(List.of(asset.id()))).singleElement()
                .satisfies(item -> assertThat(item.expiresAt()).isEqualTo(NOW.plusSeconds(60)));
        provider = key -> new MediaUrlProvider.Delivery(URI.create("https://cdn.example.org/video"), NOW);
        assertCode("SERVICE_UNAVAILABLE", () -> service().refresh(List.of(asset.id())));
    }

    @Test void aLaterProviderFailureDoesNotReturnPartialItems() {
        var first = asset("video/mp4", 1, NOW, null);
        var second = asset("video/mp4", 1, NOW, null);
        provider = key -> {
            if (key.equals(second.storageKey())) throw new MediaUrlProvider.Unavailable("provider outage");
            return new MediaUrlProvider.Delivery(URI.create("https://cdn.example.org/clip.mp4"), null);
        };
        assertCode("SERVICE_UNAVAILABLE", () -> service().refresh(List.of(first.id(), second.id())));
    }

    @Test void publishAndRevokeAreIdempotentAndRevokeIsTerminal() {
        var draft = asset("video/mp4", 1, null, null);
        var api = service();
        api.publish(draft.id());
        api.publish(draft.id());
        assertThat(api.refresh(List.of(draft.id()))).hasSize(1);
        api.revoke(draft.id());
        api.revoke(draft.id());
        assertCode("MEDIA_REMOVED", () -> api.refresh(List.of(draft.id())));
        assertCode("MEDIA_REMOVED", () -> api.publish(draft.id()));
        assertCode("MEDIA_NOT_FOUND", () -> api.publish(UUID.randomUUID()));
        assertCode("MEDIA_NOT_FOUND", () -> api.revoke(UUID.randomUUID()));
    }

    MediaAsset asset(String mime, Integer duration, Instant published, Instant revoked) {
        UUID id = UUID.randomUUID();
        var asset = new MediaAsset(id, id + "/clip.mp4", mime, duration, published, revoked);
        repository.assets.put(id, asset);
        return asset;
    }

    static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationFailure.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    static class MemoryAssets implements AssetRepository {
        final Map<UUID, MediaAsset> assets = new HashMap<>();
        int reads;
        public List<MediaAsset> findAll(Collection<UUID> ids) {
            reads++;
            return ids.stream().map(assets::get).filter(Objects::nonNull).toList();
        }
        public boolean publish(UUID id, Instant at) {
            var a = assets.get(id);
            if (a == null || a.revokedAt() != null) return false;
            assets.put(id, new MediaAsset(id, a.storageKey(), a.mimeType(), a.durationMs(),
                    a.publishedAt() == null ? at : a.publishedAt(), null));
            return true;
        }
        public boolean revoke(UUID id, Instant at) {
            var a = assets.get(id);
            if (a == null) return false;
            assets.put(id, new MediaAsset(id, a.storageKey(), a.mimeType(), a.durationMs(),
                    a.publishedAt(), a.revokedAt() == null ? at : a.revokedAt()));
            return true;
        }
    }
}
