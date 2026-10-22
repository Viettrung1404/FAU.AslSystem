package com.asl.backend.catalog.adapter.out.cursor;
import com.asl.backend.catalog.application.port.out.CatalogCursor;
import com.asl.backend.catalog.domain.UnitPosition;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class HmacCatalogCursor implements CatalogCursor {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final byte[] key;
    public HmacCatalogCursor(String secret) {
        key = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        if (key != null && key.length < 32) throw new IllegalArgumentException("Catalog cursor secret requires at least 32 bytes");
    }
    @Override public String encode(UnitPosition position, boolean prerequisites) {
        String payload = "1|public-units|position,id|" + prerequisites + "|" + position.position() + "|" + position.id();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(sign(bytes));
    }
    @Override public UnitPosition decode(String value, boolean prerequisites) {
        if (value == null || value.length() > 512 || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
        try {
            String[] parts = value.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!ENCODER.encodeToString(payload).equals(parts[0]) || !ENCODER.encodeToString(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(payload), signature)) throw invalid();
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("1") || !fields[1].equals("public-units")
                    || !fields[2].equals("position,id") || !fields[3].equals(Boolean.toString(prerequisites))) throw invalid();
            int position = Integer.parseInt(fields[4]); UUID id = UUID.fromString(fields[5]);
            if (position < 0 || !Integer.toString(position).equals(fields[4]) || !id.toString().equals(fields[5])) throw invalid();
            return new UnitPosition(position, id);
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private byte[] sign(byte[] bytes) {
        if (key == null) throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Catalog cursor configuration unavailable");
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("HMAC unavailable", error); }
    }
    private static ApplicationFailure invalid() { return new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Invalid catalog cursor"); }
}
