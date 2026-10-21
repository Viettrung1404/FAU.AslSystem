package com.asl.backend.media.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Read mapping only; content ingestion owns checksum/rights evidence and is outside the refresh API. */
@Entity
@Table(name = "media_assets", schema = "asl")
public class MediaAssetEntity {
    @Id private UUID id;
    @Column(name = "storage_key", nullable = false) private String storageKey;
    @Column(name = "mime_type", nullable = false) private String mimeType;
    @Column(name = "duration_ms") private Integer durationMs;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    protected MediaAssetEntity() { }
}
