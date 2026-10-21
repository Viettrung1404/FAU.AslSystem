package com.asl.backend.media.application.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record MediaItem(UUID assetId, URI url, Instant expiresAt, String mimeType, Integer durationMs) { }
