package com.asl.backend.media;

import com.asl.backend.media.domain.MediaAsset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MediaAssetTest {
    @Test void metadataMustSatisfyContractInvariants() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new MediaAsset(id, "clip.mp4", "video/avi", 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaAsset(id, "clip.mp4", "video/mp4", -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaAsset(id, " ", "video/mp4", 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaAsset(null, "clip.mp4", "video/mp4", 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
