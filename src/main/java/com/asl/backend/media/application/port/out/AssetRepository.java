package com.asl.backend.media.application.port.out;

import com.asl.backend.media.domain.MediaAsset;
import java.time.Instant;
import java.util.*;

public interface AssetRepository {
    List<MediaAsset> findAll(Collection<UUID> ids);
    boolean publish(UUID id, Instant at);
    boolean revoke(UUID id, Instant at);
}
