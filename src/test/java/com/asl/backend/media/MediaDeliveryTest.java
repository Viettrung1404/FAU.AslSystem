package com.asl.backend.media;

import com.asl.backend.media.adapter.out.delivery.PublicCdnUrlProvider;
import com.asl.backend.media.application.port.out.MediaUrlProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MediaDeliveryTest {
    @Test void createsHttpsUrlWithEncodedPathSegmentsAndNoInventedExpiry() {
        var delivery = new PublicCdnUrlProvider("https://cdn.example.org/media/").deliver("signs/hello world+á.mp4");
        assertThat(delivery).isNotNull();
        assertThat(delivery.url().toASCIIString()).isEqualTo("https://cdn.example.org/media/signs/hello%20world%2B%C3%A1.mp4");
        assertThat(delivery.expiresAt()).isNull();
    }
    @Test void missingConfigurationIsUnavailable() {
        assertThatThrownBy(() -> new PublicCdnUrlProvider("").deliver("hello.mp4"))
                .isInstanceOf(MediaUrlProvider.Unavailable.class);
    }
    @Test void rejectsUnsafeOrPlaceholderOrigins() {
        for (String origin : new String[]{"http://cdn.example.org", "https://cdn.invalid.", "https://cdn.invalid", "https://user:secret@cdn.example.org",
                "https://cdn.example.org/path?token=123", "https://cdn.example.org/#fragment", "not a URL"}) {
            assertThatThrownBy(() -> new PublicCdnUrlProvider(origin)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void storageKeyCannotEscapeConfiguredOriginOrDirectory() {
        var provider = new PublicCdnUrlProvider("https://cdn.example.org/media");
        for (String key : new String[]{"a\nb\nc", "", "/private", "../private", "a/../private", "https://evil.example.org/a",
                "//evil.example.org/a", "a\\private", "a//b", "a/%2e%2e/b", "a/./b", "a?secret", "a#fragment"}) {
            assertThatThrownBy(() -> provider.deliver(key)).isInstanceOf(MediaUrlProvider.Unavailable.class);
        }
    }
}
