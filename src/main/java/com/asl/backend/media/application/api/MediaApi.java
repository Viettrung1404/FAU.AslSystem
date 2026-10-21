package com.asl.backend.media.application.api;

import java.util.List;
import java.util.UUID;

public interface MediaApi {
    List<MediaItem> refresh(List<UUID> assetIds);
    void publish(UUID assetId);
    void revoke(UUID assetId);
}
