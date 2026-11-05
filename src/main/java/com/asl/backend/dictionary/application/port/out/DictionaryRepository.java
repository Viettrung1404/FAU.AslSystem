package com.asl.backend.dictionary.application.port.out;

import java.time.Instant;
import java.util.*;

public interface DictionaryRepository {
    record Position(String name, String type, UUID id, int order) { }
    record Entry(UUID id, String type, String name, String normalizedName, UUID assetId,
                 String description, boolean premium, int signCount, int order) {
        public Position position() { return new Position(normalizedName, type, id, order); }
    }
    Optional<Entry> find(UUID id, String type, Instant at);
    boolean removedSign(UUID id, Instant at);
    List<Entry> page(String scope, UUID collectionId, String query, String type, Position after, int count, Instant at);
}
