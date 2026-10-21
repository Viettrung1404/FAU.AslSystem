package com.asl.backend.media.adapter.out.delivery;

import com.asl.backend.media.application.port.out.MediaUrlProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class PublicCdnUrlProvider implements MediaUrlProvider {
    private final String baseUrl;

    public PublicCdnUrlProvider(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) { this.baseUrl = null; return; }
        URI origin = URI.create(baseUrl);
        String host = origin.getHost() == null ? "" : origin.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!"https".equalsIgnoreCase(origin.getScheme()) || host.isEmpty()
                || host.equals("invalid") || host.endsWith(".invalid") || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !origin.normalize().equals(origin)) {
            throw new IllegalArgumentException("MEDIA_PUBLIC_BASE_URL must be a public HTTPS base URL without credentials, query or fragment");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override public Delivery deliver(String storageKey) {
        if (baseUrl == null) throw new Unavailable("Media provider is not configured");
        if (storageKey == null || storageKey.isBlank() || storageKey.matches("(?s).*[:\\\\%?#\\p{Cntrl}].*")) {
            throw new Unavailable("Invalid storage key");
        }
        String[] segments = storageKey.split("/", -1);
        if (Arrays.stream(segments).anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            throw new Unavailable("Invalid storage key");
        }
        String path = Arrays.stream(segments).map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")).collect(Collectors.joining("/"));
        return new Delivery(URI.create(baseUrl + path), null);
    }
}
