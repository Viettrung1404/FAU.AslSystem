package com.asl.backend.dictionary.adapter.out.cursor;

import com.asl.backend.dictionary.application.port.out.DictionaryCursor;
import com.asl.backend.dictionary.application.port.out.DictionaryRepository.Position;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacDictionaryCursor implements DictionaryCursor {
    private final byte[] key;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    public HmacDictionaryCursor(String secret) {
        key = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        if (key != null && key.length < 32) throw new IllegalArgumentException("Dictionary cursor secret requires 32 bytes");
    }
    public String encode(String scope, Position p) {
        String value = String.join("\n", "1", hash(scope), B64.encodeToString(p.name().getBytes(StandardCharsets.UTF_8)), p.type(), p.id().toString(), Integer.toString(p.order()));
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        return B64.encodeToString(payload) + "." + B64.encodeToString(sign(payload));
    }
    public Position decode(String scope, String value) {
        try {
            if (value.length() > 4096 || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            var parts = value.split("\\."); var data = Base64.getUrlDecoder().decode(parts[0]); var signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!B64.encodeToString(data).equals(parts[0]) || !B64.encodeToString(signature).equals(parts[1]) || !MessageDigest.isEqual(sign(data),signature)) throw new IllegalArgumentException();
            var fields = new String(data,StandardCharsets.UTF_8).split("\n",-1);
            if (fields.length != 6 || !fields[0].equals("1") || !fields[1].equals(hash(scope)) || !Set.of("SIGN","COLLECTION").contains(fields[3])) throw new IllegalArgumentException();
            return new Position(new String(Base64.getUrlDecoder().decode(fields[2]),StandardCharsets.UTF_8),fields[3],UUID.fromString(fields[4]),Integer.parseInt(fields[5]));
        } catch (IllegalArgumentException error) { throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Invalid dictionary cursor"); }
    }
    private static String hash(String scope) {
        try { return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    private byte[] sign(byte[] data) {
        if (key == null) throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Dictionary cursor secret is not configured");
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(data); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
}
