package com.asl.backend.media.adapter.out.persistence;

import com.asl.backend.media.application.port.out.AssetRepository;
import com.asl.backend.media.domain.MediaAsset;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
public class JpaAssetRepository implements AssetRepository {
    @PersistenceContext private EntityManager entities;

    @Transactional(readOnly = true)
    @Override public List<MediaAsset> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        // Project directly so bulk publication changes never return stale managed entities.
        return entities.createQuery("""
                select new com.asl.backend.media.domain.MediaAsset(
                    a.id, a.storageKey, a.mimeType, a.durationMs, a.publishedAt, a.revokedAt)
                from MediaAssetEntity a where a.id in :ids
                """, MediaAsset.class).setParameter("ids", ids).getResultList();
    }

    @Override public boolean publish(UUID id, Instant at) {
        int changed = entities.createQuery("""
                update MediaAssetEntity a set a.publishedAt = :at
                where a.id = :id and a.publishedAt is null and a.revokedAt is null
                """).setParameter("id", id).setParameter("at", at).executeUpdate();
        if (changed == 1) return true;
        // Keep the row locked until the publisher's transaction ends, without creating
        // another tuple version on retries. A concurrent revoke must serialize with it.
        return !entities.createNativeQuery("""
                select 1 from asl.media_assets
                where id = :id and published_at is not null and revoked_at is null
                for update
                """).setParameter("id", id).getResultList().isEmpty();
    }

    @Override public boolean revoke(UUID id, Instant at) {
        int changed = entities.createQuery("""
                update MediaAssetEntity a set a.revokedAt = :at
                where a.id = :id and a.revokedAt is null
                """).setParameter("id", id).setParameter("at", at).executeUpdate();
        if (changed == 1) return true;
        return !entities.createNativeQuery("""
                select 1 from asl.media_assets where id = :id and revoked_at is not null for update
                """).setParameter("id", id).getResultList().isEmpty();
    }
}
