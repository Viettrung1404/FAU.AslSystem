package com.asl.backend.media.adapter.out.delivery;

import com.asl.backend.media.application.port.out.MediaUrlProvider;
import java.net.URI;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Fixed research fixture allowlist; selected only by an explicit dev profile opt-in. */
public final class DevelopmentMediaUrlProvider implements MediaUrlProvider {
    private final Map<String, URI> urls = new HashMap<>();
    private final MediaUrlProvider fallback;
    public DevelopmentMediaUrlProvider(MediaUrlProvider fallback) {
        this.fallback = fallback;
        try (var stream = getClass().getResourceAsStream("/development/media.json")) {
            var rows = JsonMapper.builder().build().readTree(stream);
            for (var row : rows) urls.put("development/" + row.get("assetId").asString(), URI.create(row.get("url").asString()));
        } catch (Exception error) { throw new IllegalStateException("Cannot load development media manifest", error); }
    }
    @Override public Delivery deliver(String key) {
        var url = urls.get(key);
        return url == null ? fallback.deliver(key) : new Delivery(url, null);
    }
}
