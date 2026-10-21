package com.asl.backend.media.adapter.out.persistence;

import com.asl.backend.media.application.port.out.AssetRepository;
import com.asl.backend.media.domain.MediaAsset;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
public class JpaAssetRepository implements AssetRepository {
    @PersistenceContext private EntityManager entities;

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
        return entities.createQuery("""
                update MediaAssetEntity a set a.publishedAt = coalesce(a.publishedAt, :at)
                where a.id = :id and a.revokedAt is null
                """).setParameter("id", id).setParameter("at", at).executeUpdate() == 1;
    }

    @Override public boolean revoke(UUID id, Instant at) {
        return entities.createQuery("""
                update MediaAssetEntity a set a.revokedAt = coalesce(a.revokedAt, :at) where a.id = :id
                """).setParameter("id", id).setParameter("at", at).executeUpdate() == 1;
    }
}
