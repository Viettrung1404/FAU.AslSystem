package com.asl.backend.media.domain;

import java.time.Instant;
import java.util.UUID;

public record MediaAsset(UUID id, String storageKey, String mimeType, Integer durationMs,
                         Instant publishedAt, Instant revokedAt) {
    public MediaAsset {
        if (id == null || storageKey == null || storageKey.isBlank()
                || mimeType == null || !java.util.Set.of("video/mp4", "image/png", "image/webp").contains(mimeType)
                || (durationMs != null && durationMs < 0)) {
            throw new IllegalArgumentException("Invalid media metadata");
        }
    }
}
