package com.asl.backend.media.application;

import com.asl.backend.media.application.api.*;
import com.asl.backend.media.application.port.out.*;
import java.time.Clock;
import java.util.*;
import com.asl.backend.media.domain.MediaAsset;
import com.asl.backend.platform.application.api.ApplicationFailure;
import static com.asl.backend.platform.application.api.ApplicationFailure.Kind.*;

public final class MediaService implements MediaApi {
    private final AssetRepository repository;
    private final MediaUrlProvider provider;
    private final Clock clock;

    public MediaService(AssetRepository repository, MediaUrlProvider provider, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.clock = clock;
    }

    public List<MediaItem> refresh(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "Invalid assetIds");
        }
        var requested = List.copyOf(ids);
        var now = clock.instant();
        Map<UUID, MediaAsset> assets = new HashMap<>();
        repository.findAll(requested).forEach(asset -> assets.put(asset.id(), asset));
        // Validate the entire batch before contacting storage, independent of request order.
        for (UUID id : requested) {
            var asset = assets.get(id);
            if (asset == null || asset.publishedAt() == null || asset.publishedAt().isAfter(now)) {
                throw notFound();
            }
        }
        if (assets.values().stream().anyMatch(asset -> asset.revokedAt() != null)) throw removed();
        try {
            return requested.stream().map(id -> {
                var asset = assets.get(id);
                var delivery = provider.deliver(asset.storageKey());
                if (delivery == null || delivery.url() == null || !delivery.url().isAbsolute()
                        || !"https".equalsIgnoreCase(delivery.url().getScheme())
                        || delivery.url().getHost() == null || delivery.url().getUserInfo() != null
                        || (delivery.expiresAt() != null && !delivery.expiresAt().isAfter(clock.instant()))) {
                    throw new MediaUrlProvider.Unavailable("Invalid delivery");
                }
                return new MediaItem(id, delivery.url(), delivery.expiresAt(), asset.mimeType(), asset.durationMs());
            }).toList();
        } catch (MediaUrlProvider.Unavailable failure) {
            throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Media delivery unavailable");
        }
    }

    public void publish(UUID id) {
        requireId(id);
        if (!repository.publish(id, clock.instant())) {
            if (repository.findAll(List.of(id)).isEmpty()) throw notFound();
            throw removed();
        }
    }

    public void revoke(UUID id) {
        requireId(id);
        if (!repository.revoke(id, clock.instant())) throw notFound();
    }

    private static void requireId(UUID id) {
        if (id == null) throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "Invalid assetId");
    }
    private static ApplicationFailure notFound() {
        return new ApplicationFailure(NOT_FOUND, "MEDIA_NOT_FOUND", "Media not found");
    }
    private static ApplicationFailure removed() {
        return new ApplicationFailure(REMOVED, "MEDIA_REMOVED", "Media removed");
    }
}
