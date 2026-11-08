package com.asl.backend.sync.application.port.out;
import java.util.*;

public interface SyncRepository {
    record Change(long sequence, String kind, String key, long revision, Map<String,Object> data) {
        public Map<String,Object> wire() { return Map.of("kind",kind,"entityKey",key,"revision",revision,"data",data); }
    }
    record Receipt(Map<String,Object> body, Change change) {}
    void lock(UUID owner);
    long watermark(UUID owner);
    Optional<Receipt> receipt(UUID owner, UUID mutationId);
    Optional<Change> current(UUID owner, String kind, String key);
    Change save(UUID owner, String kind, String key, long revision, Map<String,Object> data, String path);
    void receipt(UUID owner, UUID mutationId, Map<String,Object> body, Change change);
    List<Change> snapshot(UUID owner, long watermark, long after, int limit);
    List<Change> changes(UUID owner, long watermark, long after, int limit);
    Map<String,Object> stats(UUID owner);
}
