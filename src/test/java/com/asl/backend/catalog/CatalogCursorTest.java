package com.asl.backend.catalog;
import com.asl.backend.catalog.adapter.out.cursor.HmacCatalogCursor;
import com.asl.backend.catalog.domain.UnitPosition;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogCursorTest {
    static final String KEY = "catalog-test-secret-with-at-least-32-bytes";
    @Test void roundTripIsBoundToSortAndPrerequisiteMode() {
        var codec = new HmacCatalogCursor(KEY);
        var position = new UnitPosition(45, UUID.randomUUID());
        String encoded = codec.encode(position, true);
        assertThat(encoded).isNotBlank();
        assertThat(codec.decode(encoded, true)).isEqualTo(position);
        CatalogServiceTest.assertCode("BAD_REQUEST", () -> codec.decode(encoded, false));
        CatalogServiceTest.assertCode("BAD_REQUEST", () -> new HmacCatalogCursor(KEY + "other").decode(encoded, true));
    }
    @Test void malformedTamperedAndOversizedTokensAreBadRequest() {
        var codec = new HmacCatalogCursor(KEY);
        String token = codec.encode(new UnitPosition(1, UUID.randomUUID()), false);
        assertThat(token).isNotNull();
        for (String malformed : new String[]{"", " ", "a.b", "opaque-cursor", "x" + token, token + "=", "x".repeat(2000)})
            CatalogServiceTest.assertCode("BAD_REQUEST", () -> codec.decode(malformed, false));
    }
    @Test void missingSecretDoesNotIssueUnsignedCursorAndWeakSecretFailsFast() {
        CatalogServiceTest.assertCode("SERVICE_UNAVAILABLE", () -> new HmacCatalogCursor("").encode(new UnitPosition(1, UUID.randomUUID()), true));
        assertThatThrownBy(() -> new HmacCatalogCursor("weak")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validSignatureFromAnotherScopeOrSortIsStillRejected() throws Exception {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        for (String binding : new String[]{"search|position,id", "public-units|title,id"}) {
            byte[] payload = ("1|" + binding + "|true|1|" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String token = encoder.encodeToString(payload) + "." + encoder.encodeToString(mac.doFinal(payload));
            CatalogServiceTest.assertCode("BAD_REQUEST", () -> new HmacCatalogCursor(KEY).decode(token, true));
        }
    }
}
