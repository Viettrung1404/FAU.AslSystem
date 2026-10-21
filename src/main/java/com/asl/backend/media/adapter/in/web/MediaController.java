package com.asl.backend.media.adapter.in.web;

import com.asl.backend.media.application.api.MediaApi;
import com.asl.backend.media.application.api.MediaItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MediaController {
    private final MediaApi api;
    public MediaController(MediaApi api) { this.api = api; }

    @PostMapping("/api/v1/media/urls")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        var items = api.refresh(request.assetIds().stream().map(UUID::fromString).toList());
        return new RefreshResponse(items.stream().map(MediaResponse::from).toList());
    }

    public record RefreshRequest(@NotNull @Size(min = 1, max = 100)
            List<@NotNull @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String> assetIds) { }
    public record RefreshResponse(List<MediaResponse> items) { }
    public record MediaResponse(UUID assetId, URI url, Instant expiresAt, String mimeType, Integer durationMs) {
        static MediaResponse from(MediaItem item) {
            return new MediaResponse(item.assetId(), item.url(), item.expiresAt(), item.mimeType(), item.durationMs());
        }
    }
}
