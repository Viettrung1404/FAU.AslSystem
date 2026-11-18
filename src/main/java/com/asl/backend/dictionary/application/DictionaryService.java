package com.asl.backend.dictionary.application;

import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.dictionary.application.port.out.*;
import com.asl.backend.dictionary.application.port.out.DictionaryRepository.Entry;
import com.asl.backend.media.application.api.MediaApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

public final class DictionaryService implements DictionaryApi {
    private final DictionaryRepository repository;
    private final DictionaryCursor cursors;
    private final CollectionAccess access;
    private final MediaApi media;
    private final Clock clock;
    private final String origin;
    public DictionaryService(DictionaryRepository repository, DictionaryCursor cursors, CollectionAccess access,
                             MediaApi media, Clock clock, String origin) {
        this.repository = repository; this.cursors = cursors; this.access = access;
        this.media = media; this.clock = clock; this.origin = origin;
    }
    public static String normalize(String value) { return Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    @Override public Map<String, Object> page(String scope, UUID collectionId, String query, String type, String cursor, int limit, String authorization) {
        if (limit < 1 || limit > 100) throw bad();
        String q = "";
        if (scope.equals("search")) {
            if (query == null) throw bad();
            q = normalize(query);
            if (q.isEmpty() || q.codePointCount(0, q.length()) > 100)
                throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "VALIDATION_FAILED", "Search query must contain 1–100 characters");
            if (!Set.of("ALL", "SIGN", "COLLECTION").contains(type)) throw bad();
        }
        if (scope.equals("membership")) {
            // Validate supplied credentials even for free or missing collections.
            access.check(authorization, false);
            var collection = find(collectionId, "COLLECTION");
            if (collection.premium()) access.check(authorization, true);
        }
        String binding = scope + ":" + collectionId + ":" + type + ":" + q;
        var after = cursor == null ? null : cursors.decode(binding, cursor);
        var rows = repository.page(scope, collectionId, q, type, after, limit + 1, clock.instant());
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        var mediaMap = batchMedia(visible);
        var items = visible.stream().map(row -> {
            Object asset = row.assetId() == null ? null : mediaMap.get(row.assetId());
            return scope.equals("search")
                ? Map.<String,Object>of("type", row.type(), row.type().equals("SIGN") ? "sign" : "collection", render(row, asset))
                : render(row, asset);
        }).toList();
        Map<String,Object> result = new LinkedHashMap<>(); result.put("items", items);
        result.put("nextCursor", rows.size() > limit ? cursors.encode(binding, visible.getLast().position()) : null);
        return result;
    }
    @Override public Map<String,Object> sign(UUID id) {
        if (repository.removedSign(id, clock.instant())) throw new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_REMOVED","This sign has been removed");
        var entry = find(id, "SIGN");
        Object asset;
        try { asset = entry.assetId() == null ? null : media.refresh(List.of(entry.assetId())).getFirst(); }
        catch (ApplicationFailure failure) {
            if (failure.kind() != ApplicationFailure.Kind.NOT_FOUND && failure.kind() != ApplicationFailure.Kind.REMOVED) throw failure;
            throw new ApplicationFailure(failure.kind(), failure.kind() == ApplicationFailure.Kind.REMOVED ? "CONTENT_REMOVED" : "NOT_FOUND", "Sign media unavailable");
        }
        return render(entry, asset);
    }
    @Override public Map<String,Object> collection(UUID id) {
        var entry = find(id, "COLLECTION");
        Object asset;
        try { asset = entry.assetId() == null ? null : media.refresh(List.of(entry.assetId())).getFirst(); }
        catch (ApplicationFailure failure) {
            if (failure.kind() != ApplicationFailure.Kind.NOT_FOUND && failure.kind() != ApplicationFailure.Kind.REMOVED) throw failure;
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Published dictionary media unavailable");
        }
        return render(entry, asset);
    }
    private Entry find(UUID id, String type) {
        return repository.find(id, type, clock.instant()).orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "NOT_FOUND", "Published catalog entry not found"));
    }
    private Map<UUID, Object> batchMedia(List<Entry> entries) {
        Set<UUID> assetIds = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.assetId() != null) assetIds.add(e.assetId());
        }
        if (assetIds.isEmpty()) return Map.of();
        List<UUID> idList = new ArrayList<>(assetIds);
        Map<UUID, Object> map = new HashMap<>();
        try {
            for (int i = 0; i < idList.size(); i += 100) {
                var batch = idList.subList(i, Math.min(i + 100, idList.size()));
                media.refresh(batch).forEach(item -> map.put(item.assetId(), item));
            }
        } catch (ApplicationFailure failure) {
            if (failure.kind() != ApplicationFailure.Kind.NOT_FOUND && failure.kind() != ApplicationFailure.Kind.REMOVED) throw failure;
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Published dictionary media unavailable");
        }
        return map;
    }
    private Map<String,Object> render(Entry e, Object asset) {
        if (e.type().equals("SIGN")) return Map.of("id", e.id(), "name", e.name(), "video", asset);
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", e.id()); value.put("name", e.name()); value.put("description", e.description()); value.put("cover", asset);
        value.put("isPremium", e.premium()); value.put("signCount", e.signCount()); value.put("shareUrl", shareUrl(e.id()));
        return value;
    }
    private URI shareUrl(UUID id) {
        try {
            var uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().endsWith(".invalid") || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            return URI.create(origin.replaceAll("/$", "") + "/collections/" + id);
        } catch (RuntimeException error) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Collection share origin is not configured");
        }
    }
    private static ApplicationFailure bad() { return new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Invalid dictionary query or cursor"); }
}
