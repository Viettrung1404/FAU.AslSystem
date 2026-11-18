-- V7: Production performance indexes for seeks, collations, and foreign keys
SET LOCAL search_path = asl, public;

-- P2.4: Seek pagination on units
CREATE INDEX IF NOT EXISTS idx_units_active_seek ON asl.units(position, id)
    WHERE published_at IS NOT NULL AND retired_at IS NULL;

-- P2.2 & P2.4: Binary 'C' collation ordering for dictionary signs and collections keyset pagination
CREATE INDEX IF NOT EXISTS idx_sign_order_c ON asl.signs (search_name COLLATE "C", id)
    WHERE published_at IS NOT NULL AND retired_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_collection_order_c ON asl.collections (search_name COLLATE "C", id)
    WHERE published_at IS NOT NULL AND retired_at IS NULL;

-- Fast index lookup for user completed lesson versions
CREATE INDEX IF NOT EXISTS idx_attempt_user_version_comp ON asl.lesson_attempts(user_id, lesson_version_id)
    WHERE status = 'COMPLETED';

-- Unindexed Foreign Keys to eliminate table scans on cascading updates/deletes and foreign joins
CREATE INDEX IF NOT EXISTS idx_reports_user_id ON asl.user_reports(user_id);
CREATE INDEX IF NOT EXISTS idx_reports_lesson_version ON asl.user_reports(lesson_version_id)
    WHERE lesson_version_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_col_signs_sign_id ON asl.collection_signs(sign_id);
CREATE INDEX IF NOT EXISTS idx_lvs_video_asset ON asl.lesson_version_signs(video_asset_id);
CREATE INDEX IF NOT EXISTS idx_lvs_sign_id ON asl.lesson_version_signs(sign_id);
CREATE INDEX IF NOT EXISTS idx_lem_media_asset ON asl.lesson_exercise_media(media_asset_id);
CREATE INDEX IF NOT EXISTS idx_prereq_target ON asl.lesson_prerequisites(prerequisite_lesson_id);

-- Sync mutation receipts lookup
CREATE INDEX IF NOT EXISTS idx_smr_change_seq ON asl.sync_mutation_receipts(user_id, change_sequence);

-- Active legal documents distinct lookup
CREATE INDEX IF NOT EXISTS idx_legal_docs_active ON asl.legal_documents(document_type, published_at DESC, id DESC);
