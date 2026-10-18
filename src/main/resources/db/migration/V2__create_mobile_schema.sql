-- Full mobile v0.2 baseline reviewed against the API contract, 2026-10-18.
-- Source: db-design/ASL_Database_Schema_mobile_v0_2.sql at ADR-0009.
-- V1 owns schema creation; Flyway owns the transaction and history in public.
-- Do not edit after application; future schema changes require a new migration.
SET LOCAL search_path = asl, public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- LATER_AUTH: optional accounts. No account is created for guest learning.
CREATE TABLE app_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL CHECK (email = btrim(email) AND length(email) > 0),
  display_name varchar(100),
  time_zone text NOT NULL DEFAULT 'UTC', -- initial fallback before preferences sync
  suspended_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON app_users (lower(email));

CREATE TABLE app_settings (
  setting_key text PRIMARY KEY,
  setting_value jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_otp_challenges (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL,
  otp_hash text NOT NULL, -- keyed hash; never store the 4-digit code
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  attempt_limit integer NOT NULL CHECK (attempt_limit > 0),
  resend_count integer NOT NULL DEFAULT 0 CHECK (resend_count >= 0),
  resend_after timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > created_at)
);
CREATE INDEX idx_otp_email_created ON auth_otp_challenges (lower(email), created_at DESC);

CREATE TABLE auth_refresh_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id),
  refresh_token_hash text NOT NULL UNIQUE,
  device_label varchar(120),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz,
  CHECK (expires_at > created_at)
);
CREATE INDEX idx_refresh_user ON auth_refresh_sessions (user_id, expires_at);

-- Request/resend/verify/refresh retry receipts. Scope comes from the server
-- (operation + normalized email/challenge/session), never a client-selected owner.
CREATE TABLE api_request_receipts (
  scope_key text NOT NULL,
  client_request_id uuid NOT NULL,
  request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
  response_secret_ref text NOT NULL, -- encrypted response cache, not plaintext tokens
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (scope_key, client_request_id),
  CHECK (expires_at > created_at)
);

CREATE TABLE legal_documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_type text NOT NULL CHECK (document_type IN ('TERMS','PRIVACY','ANALYTICS_CONSENT')),
  version text NOT NULL,
  public_url text NOT NULL,
  published_at timestamptz NOT NULL,
  UNIQUE (document_type, version)
);
CREATE TABLE user_consents (
  user_id uuid NOT NULL REFERENCES app_users(id),
  document_id uuid NOT NULL REFERENCES legal_documents(id),
  accepted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, document_id)
);

-- CORE: a lean delivery catalog. Rights/release evidence belongs to the content
-- production system; keep its reference without recreating a CMS here.
CREATE TABLE media_assets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  storage_key text NOT NULL UNIQUE,
  mime_type text NOT NULL CHECK (mime_type IN ('video/mp4','image/webp','image/png')),
  duration_ms integer CHECK (duration_ms >= 0),
  sha256_checksum text NOT NULL CHECK (sha256_checksum ~ '^[0-9a-f]{64}$'),
  rights_reference text NOT NULL CHECK (length(btrim(rights_reference)) > 0),
  published_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE signs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
  search_name text GENERATED ALWAYS AS (lower(normalize(btrim(name), NFC))) STORED,
  content_kind text NOT NULL CHECK (content_kind IN ('LETTER','NUMBER','WORD','PHRASE','SENTENCE')),
  video_asset_id uuid NOT NULL REFERENCES media_assets(id),
  published_at timestamptz,
  retired_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sign_search ON signs USING gin (search_name gin_trgm_ops);
CREATE INDEX idx_sign_order ON signs (search_name, id) WHERE published_at IS NOT NULL AND retired_at IS NULL;

CREATE TABLE units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title text NOT NULL,
  position integer NOT NULL UNIQUE CHECK (position >= 0),
  published_at timestamptz,
  retired_at timestamptz
);
CREATE TABLE lessons (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  unit_id uuid NOT NULL REFERENCES units(id),
  position integer NOT NULL CHECK (position >= 0),
  retired_at timestamptz,
  UNIQUE (unit_id, position)
);
CREATE TABLE lesson_prerequisites (
  lesson_id uuid NOT NULL REFERENCES lessons(id),
  prerequisite_lesson_id uuid NOT NULL REFERENCES lessons(id),
  PRIMARY KEY (lesson_id, prerequisite_lesson_id),
  CHECK (lesson_id <> prerequisite_lesson_id)
);
-- Prerequisites drive Flutter unlock UI; they are not authorization gates.
-- Dev policy (ADR-0007): preserve these rows; catalog returns [] when
-- ASL_LESSON_PREREQUISITES_ENABLED=false. true returns stored prerequisites.
-- This is planned API projection behavior, not a SQL/session setting; no DDL change.
CREATE TABLE lesson_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lesson_id uuid NOT NULL REFERENCES lessons(id),
  version_no integer NOT NULL CHECK (version_no > 0),
  title text NOT NULL,
  lesson_type text NOT NULL CHECK (lesson_type IN ('LESSON','ALPHABET','REVIEW','CONVERSATION')),
  scoring_policy_version integer NOT NULL DEFAULT 1 CHECK (scoring_policy_version = 1),
  published_at timestamptz,
  is_current boolean NOT NULL DEFAULT false,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (lesson_id, version_no),
  UNIQUE (id, lesson_id),
  CHECK (NOT is_current OR (published_at IS NOT NULL AND revoked_at IS NULL)),
  CHECK (revoked_at IS NULL OR published_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_current_lesson_version ON lesson_versions(lesson_id) WHERE is_current;

-- Pin sign name/video/kind too, so old lesson versions do not silently change.
CREATE TABLE lesson_version_signs (
  lesson_version_id uuid NOT NULL REFERENCES lesson_versions(id),
  sign_id uuid NOT NULL REFERENCES signs(id),
  name_snapshot varchar(200) NOT NULL,
  kind_snapshot text NOT NULL CHECK (kind_snapshot IN ('LETTER','NUMBER','WORD','PHRASE','SENTENCE')),
  video_asset_id uuid NOT NULL REFERENCES media_assets(id),
  position integer NOT NULL CHECK (position >= 0),
  PRIMARY KEY (lesson_version_id, sign_id),
  UNIQUE (lesson_version_id, position)
);
CREATE TABLE lesson_exercises (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lesson_version_id uuid NOT NULL REFERENCES lesson_versions(id),
  position integer NOT NULL CHECK (position >= 0),
  exercise_type text NOT NULL CHECK (exercise_type IN (
    'VIDEO_TO_WORD','WORD_TO_VIDEO','VIDEO_TO_IMAGE','MATCH_PAIRS','FILL_GAP',
    'ORDER_SIGNS','VIDEO_TO_MEANING','KNOWLEDGE','SPELL','TIP','ENCOURAGEMENT','REVIEW_INTRO','PRAISE')),
  prompt text,
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  answer_key jsonb CHECK (jsonb_typeof(answer_key) = 'object'),
  points_per_correct integer NOT NULL CHECK (points_per_correct BETWEEN 0 AND 1000),
  UNIQUE (lesson_version_id, position),
  UNIQUE (id, lesson_version_id),
  CHECK ((exercise_type IN ('TIP','ENCOURAGEMENT','REVIEW_INTRO','PRAISE') AND answer_key IS NULL AND points_per_correct = 0)
    OR (exercise_type NOT IN ('TIP','ENCOURAGEMENT','REVIEW_INTRO','PRAISE') AND answer_key IS NOT NULL AND answer_key <> '{}'))
);
CREATE TABLE exercise_signs (
  lesson_version_id uuid NOT NULL,
  exercise_id uuid NOT NULL,
  sign_id uuid NOT NULL,
  PRIMARY KEY (exercise_id, sign_id),
  FOREIGN KEY (exercise_id, lesson_version_id) REFERENCES lesson_exercises(id, lesson_version_id),
  FOREIGN KEY (lesson_version_id, sign_id) REFERENCES lesson_version_signs(lesson_version_id, sign_id)
);
CREATE TABLE lesson_exercise_media (
  lesson_version_id uuid NOT NULL,
  exercise_id uuid NOT NULL,
  slot_key text NOT NULL CHECK (slot_key ~ '^[A-Za-z0-9_-]{1,64}$'),
  media_asset_id uuid NOT NULL REFERENCES media_assets(id),
  PRIMARY KEY (exercise_id, slot_key),
  FOREIGN KEY (exercise_id, lesson_version_id) REFERENCES lesson_exercises(id, lesson_version_id)
);
CREATE TABLE conversation_turns (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lesson_version_id uuid NOT NULL REFERENCES lesson_versions(id),
  position integer NOT NULL CHECK (position >= 0),
  speaker_side text NOT NULL CHECK (speaker_side IN ('PARTNER','LEARNER')),
  gloss_caption text NOT NULL,
  english_translation text NOT NULL,
  video_asset_id uuid NOT NULL REFERENCES media_assets(id),
  UNIQUE (lesson_version_id, position)
);

CREATE TABLE collections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL CHECK (length(btrim(name)) > 0),
  search_name text GENERATED ALWAYS AS (lower(normalize(btrim(name), NFC))) STORED,
  description varchar(2000) NOT NULL DEFAULT '',
  cover_asset_id uuid REFERENCES media_assets(id),
  is_premium boolean NOT NULL DEFAULT false,
  published_at timestamptz,
  retired_at timestamptz
);
CREATE INDEX idx_collection_search ON collections USING gin (search_name gin_trgm_ops);
CREATE INDEX idx_collection_order ON collections(search_name, id) WHERE published_at IS NOT NULL AND retired_at IS NULL;
CREATE TABLE collection_signs (
  collection_id uuid NOT NULL REFERENCES collections(id),
  sign_id uuid NOT NULL REFERENCES signs(id),
  position integer NOT NULL CHECK (position >= 0),
  PRIMARY KEY (collection_id, sign_id),
  UNIQUE (collection_id, position)
);
-- API checks PREMIUM_COLLECTIONS before each membership page. All signs remain public.
-- Practice is generated on demand from lesson_version_signs. No practice history tables.

-- LATER_SYNC: only accepted account-scoped copies of local data live here.
CREATE TABLE lesson_attempts (
  user_id uuid NOT NULL REFERENCES app_users(id),
  attempt_id uuid NOT NULL, -- generated on the device; replay gets a NEW ID
  lesson_id uuid NOT NULL,
  lesson_version_id uuid NOT NULL,
  status text NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','ABANDONED')),
  started_at timestamptz NOT NULL,
  client_updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  completion_time_zone text NOT NULL,
  completion_local_date date, -- stamped once from completed_at + saved timezone
  checkpoint jsonb NOT NULL CHECK (jsonb_typeof(checkpoint) = 'object'),
  max_points integer NOT NULL CHECK (max_points >= 0), -- computed from frozen content
  earned_points integer NOT NULL CHECK (earned_points BETWEEN 0 AND max_points),
  score_percent numeric(5,2) GENERATED ALWAYS AS
    (CASE WHEN status = 'COMPLETED' AND max_points > 0 THEN round(100.0 * earned_points / max_points, 2) ELSE NULL END) STORED,
  revision bigint NOT NULL CHECK (revision > 0),
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, attempt_id),
  FOREIGN KEY (lesson_version_id, lesson_id) REFERENCES lesson_versions(id, lesson_id),
  CHECK (started_at <= client_updated_at),
  CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL AND completion_local_date IS NOT NULL
      AND completed_at BETWEEN started_at AND client_updated_at)
    OR (status <> 'COMPLETED' AND completed_at IS NULL AND completion_local_date IS NULL AND earned_points = 0))
);
CREATE INDEX idx_attempt_completed ON lesson_attempts(user_id, lesson_id, completion_local_date) WHERE status = 'COMPLETED';
CREATE INDEX idx_attempt_version ON lesson_attempts(lesson_version_id);

CREATE TABLE completion_ledger (
  user_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  points integer NOT NULL CHECK (points >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, attempt_id),
  FOREIGN KEY (user_id, attempt_id) REFERENCES lesson_attempts(user_id, attempt_id)
);

CREATE TABLE favorites (
  user_id uuid NOT NULL REFERENCES app_users(id),
  target_type text NOT NULL CHECK (target_type IN ('SIGN','COLLECTION')),
  target_id uuid NOT NULL,
  sign_id uuid REFERENCES signs(id),
  collection_id uuid REFERENCES collections(id),
  is_favorite boolean NOT NULL, -- false is retained for sync
  revision bigint NOT NULL CHECK (revision > 0),
  PRIMARY KEY (user_id, target_type, target_id),
  CHECK ((target_type = 'SIGN' AND sign_id IS NOT NULL AND sign_id = target_id AND collection_id IS NULL)
    OR (target_type = 'COLLECTION' AND collection_id IS NOT NULL AND collection_id = target_id AND sign_id IS NULL))
);

-- Whole-resource replace matches the API. Ordered UUID array avoids an extra
-- item table; trigger below checks uniqueness, references and tombstone rules.
CREATE TABLE personal_collections (
  user_id uuid NOT NULL REFERENCES app_users(id),
  id uuid NOT NULL,
  name varchar(100) NOT NULL CHECK (length(name) > 0),
  sign_ids uuid[] NOT NULL DEFAULT '{}',
  deleted boolean NOT NULL DEFAULT false,
  revision bigint NOT NULL CHECK (revision > 0),
  PRIMARY KEY (user_id, id),
  CHECK (cardinality(sign_ids) <= 1000),
  CHECK (NOT deleted OR cardinality(sign_ids) = 0)
);
CREATE TABLE user_preferences (
  user_id uuid PRIMARY KEY REFERENCES app_users(id),
  haptics_enabled boolean NOT NULL,
  daily_reminder_enabled boolean NOT NULL,
  daily_reminder_local_time text CHECK (daily_reminder_local_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  learning_goal_days smallint NOT NULL CHECK (learning_goal_days IN (7,14,30,50)),
  time_zone text NOT NULL,
  revision bigint NOT NULL CHECK (revision > 0)
);

CREATE TABLE sync_streams (
  user_id uuid PRIMARY KEY REFERENCES app_users(id),
  last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);
-- Full entity history: also supplies a stable snapshot at ANY saved watermark.
-- No automatic pruning in this schema. Do not delete old versions without a
-- snapshot-baseline/expiry design; retain deltas at least 90 days.
CREATE TABLE sync_changes (
  user_id uuid NOT NULL REFERENCES sync_streams(user_id),
  sequence_no bigint NOT NULL,
  kind text NOT NULL CHECK (kind IN ('LESSON_ATTEMPT','FAVORITE','PERSONAL_COLLECTION','PREFERENCES')),
  entity_key text NOT NULL,
  revision bigint NOT NULL CHECK (revision > 0),
  data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object'), -- exact contract data, including tombstones
  published_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, sequence_no),
  UNIQUE (user_id, kind, entity_key, revision)
);
CREATE INDEX idx_sync_snapshot ON sync_changes(user_id, kind, entity_key, sequence_no DESC);
CREATE TABLE sync_mutation_receipts (
  user_id uuid NOT NULL,
  mutation_id uuid NOT NULL,
  mutation_body jsonb NOT NULL CHECK (jsonb_typeof(mutation_body) = 'object'),
  change_sequence bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, mutation_id),
  FOREIGN KEY (user_id, change_sequence) REFERENCES sync_changes(user_id, sequence_no)
);

CREATE TABLE user_reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  client_report_id uuid NOT NULL UNIQUE, -- global UUID also dedupes guest retries
  user_id uuid REFERENCES app_users(id),
  target_type text NOT NULL CHECK (target_type IN ('SIGN','LESSON','EXERCISE','COLLECTION')),
  target_id uuid NOT NULL,
  lesson_version_id uuid REFERENCES lesson_versions(id),
  reason text NOT NULL CHECK (reason IN ('WRONG_CONTENT','VIDEO_PROBLEM','WRONG_ANSWER','OTHER')),
  message varchar(2000) NOT NULL CHECK (length(message) > 0),
  request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
  received_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((target_type = 'EXERCISE') = (lesson_version_id IS NOT NULL))
);

-- LATER_PREMIUM: one entitlement, obtained from verified Store subscriptions.
CREATE TABLE store_products (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  store text NOT NULL CHECK (store IN ('APP_STORE','GOOGLE_PLAY')),
  external_product_id text NOT NULL,
  base_plan_id text,
  period text NOT NULL CHECK (period IN ('MONTHLY','QUARTERLY','YEARLY')),
  is_active boolean NOT NULL DEFAULT true,
  UNIQUE NULLS NOT DISTINCT (store, external_product_id, base_plan_id),
  UNIQUE (id, store),
  CHECK (store <> 'APP_STORE' OR base_plan_id IS NULL)
);
CREATE TABLE subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id),
  store text NOT NULL CHECK (store IN ('APP_STORE','GOOGLE_PLAY')),
  store_product_id uuid NOT NULL,
  external_subscription_key text NOT NULL,
  status text NOT NULL CHECK (status IN ('ACTIVE','GRACE_PERIOD','EXPIRED','REVOKED','PENDING')),
  valid_from timestamptz,
  expires_at timestamptz, -- store-verified entitlement end, including grace where applicable
  auto_renew boolean NOT NULL DEFAULT false,
  verified_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (store, external_subscription_key),
  UNIQUE (id, user_id, store),
  FOREIGN KEY (store_product_id, store) REFERENCES store_products(id, store),
  CHECK (expires_at IS NULL OR (valid_from IS NOT NULL AND expires_at > valid_from)),
  CHECK (status NOT IN ('ACTIVE','GRACE_PERIOD') OR (verified_at IS NOT NULL AND valid_from IS NOT NULL))
);
CREATE INDEX idx_subscription_owner ON subscriptions(user_id, status, expires_at);
CREATE TABLE store_purchases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id),
  store text NOT NULL CHECK (store IN ('APP_STORE','GOOGLE_PLAY')),
  requested_product_id text NOT NULL,
  store_product_id uuid,
  subscription_id uuid,
  proof_hash bytea NOT NULL CHECK (octet_length(proof_hash) = 32),
  proof_secret_ref text NOT NULL,
  external_transaction_id text,
  purchase_token_hash text,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
  reason text,
  verified_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (store, proof_hash),
  FOREIGN KEY (store_product_id, store) REFERENCES store_products(id, store),
  FOREIGN KEY (subscription_id, user_id, store) REFERENCES subscriptions(id, user_id, store),
  CHECK (status <> 'VERIFIED' OR (verified_at IS NOT NULL AND subscription_id IS NOT NULL AND store_product_id IS NOT NULL)),
  CHECK (status <> 'VERIFIED' OR
    (store = 'APP_STORE' AND external_transaction_id IS NOT NULL) OR
    (store = 'GOOGLE_PLAY' AND purchase_token_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uq_purchase_transaction ON store_purchases(store, external_transaction_id) WHERE external_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_token ON store_purchases(store, purchase_token_hash) WHERE purchase_token_hash IS NOT NULL;
CREATE INDEX idx_purchase_owner ON store_purchases(user_id, created_at DESC);
-- Internal Store delivery inbox is required for renew/refund/revoke correctness,
-- even though callback endpoints are not mobile endpoints.
CREATE TABLE store_webhook_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  store text NOT NULL CHECK (store IN ('APP_STORE','GOOGLE_PLAY')),
  external_event_id text NOT NULL,
  payload_secret_ref text NOT NULL,
  processing_status text NOT NULL DEFAULT 'PENDING' CHECK (processing_status IN ('PENDING','PROCESSED','FAILED')),
  received_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  failure_reason text,
  UNIQUE (store, external_event_id)
);

-- Shared invariants. These are service-owned tables, never exposed as direct
-- mobile SQL access. API authorization and JSON-schema validation remain required.
CREATE FUNCTION validate_time_zone(p_zone text) RETURNS void LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = p_zone) THEN
    RAISE EXCEPTION 'Unknown IANA time zone: %', p_zone USING ERRCODE = '23514';
  END IF;
END $$;
CREATE FUNCTION check_account_time_zone() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN PERFORM validate_time_zone(NEW.time_zone); RETURN NEW; END $$;
CREATE TRIGGER user_time_zone BEFORE INSERT OR UPDATE ON app_users FOR EACH ROW EXECUTE FUNCTION check_account_time_zone();
CREATE TRIGGER preference_time_zone BEFORE INSERT OR UPDATE ON user_preferences FOR EACH ROW EXECUTE FUNCTION check_account_time_zone();

CREATE FUNCTION protect_published_media() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Retain media IDs; revoke instead of deleting' USING ERRCODE = '23514';
  END IF;
  IF OLD.published_at IS NOT NULL AND
    (to_jsonb(NEW) - 'revoked_at') IS DISTINCT FROM (to_jsonb(OLD) - 'revoked_at') THEN
    RAISE EXCEPTION 'Published media bytes and metadata are immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
    RAISE EXCEPTION 'Revoked media cannot be restored in place' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER protect_media BEFORE UPDATE OR DELETE ON media_assets FOR EACH ROW EXECUTE FUNCTION protect_published_media();

CREATE FUNCTION protect_version_child() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE vid uuid; stamp timestamptz;
BEGIN
  vid := CASE WHEN TG_OP = 'DELETE' THEN OLD.lesson_version_id ELSE NEW.lesson_version_id END;
  IF TG_OP = 'UPDATE' AND NEW.lesson_version_id <> OLD.lesson_version_id THEN
    RAISE EXCEPTION 'Cannot move content between versions' USING ERRCODE = '23514';
  END IF;
  SELECT published_at INTO stamp FROM lesson_versions WHERE id = vid FOR UPDATE;
  IF stamp IS NOT NULL THEN RAISE EXCEPTION 'Published version content is immutable' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['lesson_version_signs','lesson_exercises','exercise_signs','lesson_exercise_media','conversation_turns'] LOOP
    EXECUTE format('CREATE TRIGGER protect_content BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_version_child()', tbl);
  END LOOP;
END $$;
CREATE FUNCTION protect_lesson_version() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.published_at IS NOT NULL THEN RAISE EXCEPTION 'Retain published versions' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'INSERT' AND NEW.published_at IS NOT NULL THEN
    RAISE EXCEPTION 'Create draft, attach content, then publish' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.published_at IS NOT NULL AND
      (to_jsonb(NEW) - ARRAY['is_current','revoked_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['is_current','revoked_at']) THEN
      RAISE EXCEPTION 'Published version is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
      RAISE EXCEPTION 'Revoked version cannot be restored' USING ERRCODE = '23514';
    END IF;
    IF OLD.published_at IS NULL AND NEW.published_at IS NOT NULL THEN
      IF (NEW.lesson_type = 'CONVERSATION' AND
          (EXISTS (SELECT 1 FROM lesson_exercises WHERE lesson_version_id = NEW.id) OR
           NOT EXISTS (SELECT 1 FROM conversation_turns WHERE lesson_version_id = NEW.id))) OR
         (NEW.lesson_type <> 'CONVERSATION' AND
          (EXISTS (SELECT 1 FROM conversation_turns WHERE lesson_version_id = NEW.id) OR
           NOT EXISTS (SELECT 1 FROM lesson_exercises WHERE lesson_version_id = NEW.id))) THEN
        RAISE EXCEPTION 'Lesson content does not match lesson type' USING ERRCODE = '23514';
      END IF;
      IF EXISTS (
        SELECT 1 FROM media_assets m JOIN (
          SELECT video_asset_id AS asset_id FROM lesson_version_signs WHERE lesson_version_id = NEW.id
          UNION SELECT media_asset_id FROM lesson_exercise_media WHERE lesson_version_id = NEW.id
          UNION SELECT video_asset_id FROM conversation_turns WHERE lesson_version_id = NEW.id
        ) refs ON refs.asset_id = m.id WHERE m.published_at IS NULL OR m.revoked_at IS NOT NULL
      ) THEN RAISE EXCEPTION 'Lesson media must be published and available' USING ERRCODE = '23514'; END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER protect_version BEFORE INSERT OR UPDATE OR DELETE ON lesson_versions FOR EACH ROW EXECUTE FUNCTION protect_lesson_version();

CREATE FUNCTION validate_personal_collection() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.deleted AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'Personal collection tombstone is terminal' USING ERRCODE = '23514';
  END IF;
  IF cardinality(NEW.sign_ids) <> (SELECT count(DISTINCT input.sign_id) FROM unnest(NEW.sign_ids) AS input(sign_id))
    OR EXISTS (SELECT 1 FROM unnest(NEW.sign_ids) AS input(sign_id) WHERE NOT EXISTS (SELECT 1 FROM signs s WHERE s.id = input.sign_id)) THEN
    RAISE EXCEPTION 'signIds must be distinct valid non-null sign IDs' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER personal_collection_validation BEFORE INSERT OR UPDATE ON personal_collections FOR EACH ROW EXECUTE FUNCTION validate_personal_collection();
CREATE FUNCTION retain_sign_id() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN RAISE EXCEPTION 'Retire signs; retain IDs referenced by sync and content' USING ERRCODE = '23514'; END $$;
CREATE TRIGGER retain_sign BEFORE DELETE ON signs FOR EACH ROW EXECUTE FUNCTION retain_sign_id();

CREATE FUNCTION validate_report_target() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE valid boolean;
BEGIN
  CASE NEW.target_type
    WHEN 'SIGN' THEN SELECT EXISTS(SELECT 1 FROM signs WHERE id = NEW.target_id) INTO valid;
    WHEN 'LESSON' THEN SELECT EXISTS(SELECT 1 FROM lessons WHERE id = NEW.target_id) INTO valid;
    WHEN 'COLLECTION' THEN SELECT EXISTS(SELECT 1 FROM collections WHERE id = NEW.target_id) INTO valid;
    WHEN 'EXERCISE' THEN SELECT EXISTS(SELECT 1 FROM lesson_exercises WHERE id = NEW.target_id AND lesson_version_id = NEW.lesson_version_id) INTO valid;
    ELSE valid := false;
  END CASE;
  IF NOT valid THEN RAISE EXCEPTION 'Invalid report target/version' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER report_target BEFORE INSERT OR UPDATE ON user_reports FOR EACH ROW EXECUTE FUNCTION validate_report_target();

CREATE FUNCTION check_entity_revision() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND (
    NEW.user_id <> OLD.user_id OR
    (to_jsonb(NEW)->'id') IS DISTINCT FROM (to_jsonb(OLD)->'id') OR
    (to_jsonb(NEW)->'target_type') IS DISTINCT FROM (to_jsonb(OLD)->'target_type') OR
    (to_jsonb(NEW)->'target_id') IS DISTINCT FROM (to_jsonb(OLD)->'target_id')) THEN
    RAISE EXCEPTION 'Sync entity identity cannot change' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'INSERT' AND NEW.revision <> 1 THEN RAISE EXCEPTION 'Initial revision must be 1' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'UPDATE' AND NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'Revision must advance by one' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['lesson_attempts','favorites','personal_collections','user_preferences'] LOOP
    EXECUTE format('CREATE TRIGGER entity_revision BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION check_entity_revision()', tbl);
  END LOOP;
END $$;

CREATE FUNCTION assign_sync_sequence() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE previous_revision bigint;
BEGIN
  -- Per-account lock serializes commits. A global sequence alone could expose
  -- sequence N+1 before transaction N commits and make a cursor skip a change.
  UPDATE sync_streams SET last_sequence = last_sequence + 1 WHERE user_id = NEW.user_id
    RETURNING last_sequence INTO NEW.sequence_no;
  IF NOT FOUND THEN RAISE EXCEPTION 'Initialize account sync stream first' USING ERRCODE = '23503'; END IF;
  SELECT revision INTO previous_revision FROM sync_changes
    WHERE user_id = NEW.user_id AND kind = NEW.kind AND entity_key = NEW.entity_key
    ORDER BY sequence_no DESC LIMIT 1;
  IF NEW.revision <> coalesce(previous_revision, 0) + 1 THEN
    RAISE EXCEPTION 'Sync entity revision conflict' USING ERRCODE = '23514';
  END IF;
  NEW.published_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER sync_sequence BEFORE INSERT ON sync_changes FOR EACH ROW EXECUTE FUNCTION assign_sync_sequence();

CREATE FUNCTION protect_purchase_owner() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF NEW.user_id <> OLD.user_id OR NEW.store <> OLD.store THEN
    RAISE EXCEPTION 'Store ownership cannot be transferred by restore' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER subscription_owner BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE FUNCTION protect_purchase_owner();
CREATE TRIGGER purchase_owner BEFORE UPDATE ON store_purchases FOR EACH ROW EXECUTE FUNCTION protect_purchase_owner();

CREATE FUNCTION retain_catalog_identity() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF NEW.id <> OLD.id THEN RAISE EXCEPTION 'Catalog identity cannot change' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sign_identity BEFORE UPDATE ON signs FOR EACH ROW EXECUTE FUNCTION retain_catalog_identity();

CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END $$;
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['app_users','app_settings','signs','subscriptions'] LOOP
    EXECUTE format('CREATE TRIGGER stamp_update BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at()', tbl);
  END LOOP;
END $$;

-- Accepted attempt validation, completion ledger and derived stats follow below.
-- The service MUST first validate LessonAttempt/LessonCheckpoint using OpenAPI,
-- reject unknown fields and compare submitted scorePercent with the computed value.
CREATE FUNCTION validate_lesson_attempt() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE
  v lesson_versions%ROWTYPE; e lesson_exercises%ROWTYPE;
  cp jsonb; a jsonb; mp jsonb; pair jsonb; expected jsonb;
  correct boolean; temporary integer := 0; maximum integer := 0;
  first_ids uuid[] := '{}'; wrong_ids uuid[] := '{}'; match_ids uuid[] := '{}';
  pending_ids uuid[]; resolved_ids uuid[]; done_ids uuid[]; all_step_ids uuid[];
  left_ids text[]; right_ids text[]; n integer; required_count integer;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status IN ('COMPLETED','ABANDONED') THEN
      RAISE EXCEPTION 'ATTEMPT_IMMUTABLE: terminal attempt cannot change' USING ERRCODE = '23514';
    END IF;
    IF (NEW.user_id, NEW.attempt_id, NEW.lesson_id, NEW.lesson_version_id, NEW.started_at)
      IS DISTINCT FROM (OLD.user_id, OLD.attempt_id, OLD.lesson_id, OLD.lesson_version_id, OLD.started_at) THEN
      RAISE EXCEPTION 'Attempt identity/version/start cannot change' USING ERRCODE = '23514';
    END IF;
  END IF;
  PERFORM validate_time_zone(NEW.completion_time_zone);
  IF NEW.client_updated_at > now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'Client time is too far in the future' USING ERRCODE = '23514';
  END IF;
  SELECT * INTO v FROM lesson_versions WHERE id = NEW.lesson_version_id FOR SHARE;
  IF v.published_at IS NULL OR v.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'Version unavailable; preserve previously accepted history' USING ERRCODE = '23514';
  END IF;
  cp := NEW.checkpoint;
  IF NOT (cp ?& ARRAY['phase','currentStepId','firstAnswers','reviewQueue','reviewResolvedExerciseIds',
    'matchingProgress','draftAnswer','slowPlayback','temporaryPoints','completedStepIds']) OR
    cp->>'phase' NOT IN ('QUESTION','FEEDBACK','REVIEW','CONVERSATION','RESULT') OR
    jsonb_typeof(cp->'firstAnswers') <> 'array' OR jsonb_typeof(cp->'matchingProgress') <> 'array' OR
    jsonb_typeof(cp->'reviewQueue') <> 'array' OR jsonb_typeof(cp->'reviewResolvedExerciseIds') <> 'array' OR
    jsonb_typeof(cp->'completedStepIds') <> 'array' OR jsonb_typeof(cp->'temporaryPoints') <> 'number' THEN
    RAISE EXCEPTION 'Invalid checkpoint shape' USING ERRCODE = '23514';
  END IF;
  SELECT coalesce(array_agg(id), '{}'::uuid[]) INTO all_step_ids FROM (
    SELECT id FROM lesson_exercises WHERE lesson_version_id = v.id
    UNION ALL SELECT id FROM conversation_turns WHERE lesson_version_id = v.id
  ) steps;
  SELECT coalesce(array_agg(x::uuid), '{}'::uuid[]) INTO pending_ids FROM jsonb_array_elements_text(cp->'reviewQueue') x;
  SELECT coalesce(array_agg(x::uuid), '{}'::uuid[]) INTO resolved_ids FROM jsonb_array_elements_text(cp->'reviewResolvedExerciseIds') x;
  SELECT coalesce(array_agg(x::uuid), '{}'::uuid[]) INTO done_ids FROM jsonb_array_elements_text(cp->'completedStepIds') x;
  IF cardinality(done_ids) <> (SELECT count(DISTINCT id) FROM unnest(done_ids) id)
    OR cardinality(pending_ids) <> (SELECT count(DISTINCT id) FROM unnest(pending_ids) id)
    OR cardinality(resolved_ids) <> (SELECT count(DISTINCT id) FROM unnest(resolved_ids) id)
    OR NOT done_ids <@ all_step_ids OR pending_ids && resolved_ids
    OR (cp->>'currentStepId' IS NOT NULL AND NOT (cp->>'currentStepId')::uuid = ANY(all_step_ids)) THEN
    RAISE EXCEPTION 'Invalid checkpoint step references/duplicates' USING ERRCODE = '23514';
  END IF;
  FOR e IN SELECT * FROM lesson_exercises WHERE lesson_version_id = v.id LOOP
    IF e.exercise_type = 'MATCH_PAIRS' THEN
      SELECT count(*) INTO n FROM jsonb_object_keys(e.answer_key->'pairs');
      maximum := maximum + n * e.points_per_correct;
    ELSE maximum := maximum + e.points_per_correct;
    END IF;
  END LOOP;
  FOR a IN SELECT value FROM jsonb_array_elements(cp->'firstAnswers') LOOP
    SELECT * INTO e FROM lesson_exercises WHERE id = (a->>'exerciseId')::uuid AND lesson_version_id = v.id;
    IF NOT FOUND OR e.exercise_type IN ('MATCH_PAIRS','TIP','ENCOURAGEMENT','REVIEW_INTRO','PRAISE')
      OR e.id = ANY(first_ids) THEN
      RAISE EXCEPTION 'Invalid/duplicate first answer' USING ERRCODE = '23514';
    END IF;
    first_ids := array_append(first_ids, e.id);
    IF e.exercise_type IN ('FILL_GAP','ORDER_SIGNS','SPELL') THEN
      expected := CASE WHEN e.exercise_type IN ('ORDER_SIGNS','SPELL') THEN e.answer_key->'orderedTokenIds' ELSE e.answer_key->'correctTokenIds' END;
      IF a->'answer'->>'type' IS DISTINCT FROM 'TOKENS' OR expected IS NULL THEN
        RAISE EXCEPTION 'Token answer/key required' USING ERRCODE = '23514';
      END IF;
      correct := a->'answer'->'tokenIds' = expected;
    ELSE
      IF a->'answer'->>'type' IS DISTINCT FROM 'CHOICE' OR e.answer_key->>'correctOptionId' IS NULL THEN
        RAISE EXCEPTION 'Choice answer/key required' USING ERRCODE = '23514';
      END IF;
      correct := a->'answer'->>'optionId' = e.answer_key->>'correctOptionId';
    END IF;
    IF correct IS NULL OR (a->>'isCorrect')::boolean IS DISTINCT FROM correct THEN
      RAISE EXCEPTION 'First answer correctness mismatch' USING ERRCODE = '23514';
    END IF;
    IF correct THEN temporary := temporary + e.points_per_correct;
    ELSE wrong_ids := array_append(wrong_ids, e.id); END IF;
  END LOOP;
  FOR mp IN SELECT value FROM jsonb_array_elements(cp->'matchingProgress') LOOP
    SELECT * INTO e FROM lesson_exercises WHERE id = (mp->>'exerciseId')::uuid AND lesson_version_id = v.id;
    IF NOT FOUND OR e.exercise_type <> 'MATCH_PAIRS' OR e.id = ANY(match_ids) THEN
      RAISE EXCEPTION 'Invalid/duplicate matching exercise' USING ERRCODE = '23514';
    END IF;
    match_ids := array_append(match_ids, e.id); left_ids := '{}'; right_ids := '{}';
    FOR pair IN SELECT value FROM jsonb_array_elements(mp->'matchedPairs') LOOP
      IF pair->>'leftId' IS NULL OR pair->>'rightId' IS NULL OR
        (e.answer_key->'pairs'->>(pair->>'leftId')) IS DISTINCT FROM pair->>'rightId' OR
        pair->>'leftId' = ANY(left_ids) OR pair->>'rightId' = ANY(right_ids) THEN
        RAISE EXCEPTION 'Invalid/duplicate matched pair' USING ERRCODE = '23514';
      END IF;
      left_ids := array_append(left_ids, pair->>'leftId'); right_ids := array_append(right_ids, pair->>'rightId');
      temporary := temporary + e.points_per_correct;
    END LOOP;
    IF NEW.status = 'COMPLETED' AND cardinality(left_ids) <> (SELECT count(*) FROM jsonb_object_keys(e.answer_key->'pairs')) THEN
      RAISE EXCEPTION 'Matching incomplete' USING ERRCODE = '23514';
    END IF;
  END LOOP;
  IF NOT pending_ids <@ wrong_ids OR NOT resolved_ids <@ wrong_ids OR
    ((cp->>'phase' = 'REVIEW' OR NEW.status = 'COMPLETED') AND NOT wrong_ids <@ (pending_ids || resolved_ids)) THEN
    RAISE EXCEPTION 'Review state does not match wrong first answers' USING ERRCODE = '23514';
  END IF;
  IF (cp->>'temporaryPoints')::integer IS DISTINCT FROM temporary OR NEW.max_points <> maximum THEN
    RAISE EXCEPTION 'Temporary/max points mismatch' USING ERRCODE = '23514';
  END IF;
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*) INTO required_count FROM lesson_exercises WHERE lesson_version_id = v.id
      AND exercise_type NOT IN ('TIP','ENCOURAGEMENT','REVIEW_INTRO','PRAISE','MATCH_PAIRS');
    IF cp->>'phase' IS DISTINCT FROM 'RESULT' OR cp->'currentStepId' IS DISTINCT FROM 'null'::jsonb
      OR cp->'draftAnswer' IS DISTINCT FROM 'null'::jsonb OR cardinality(pending_ids) <> 0
      OR NOT all_step_ids <@ done_ids OR cardinality(first_ids) <> required_count
      OR cardinality(match_ids) <> (SELECT count(*) FROM lesson_exercises WHERE lesson_version_id = v.id AND exercise_type = 'MATCH_PAIRS')
      OR NEW.earned_points <> temporary THEN
      RAISE EXCEPTION 'Completion requires all steps/review and correct total' USING ERRCODE = '23514';
    END IF;
    NEW.completion_local_date := (NEW.completed_at AT TIME ZONE NEW.completion_time_zone)::date;
  ELSE NEW.completion_local_date := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER attempt_validation BEFORE INSERT OR UPDATE ON lesson_attempts FOR EACH ROW EXECUTE FUNCTION validate_lesson_attempt();

CREATE FUNCTION write_completion_ledger() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF NEW.status = 'COMPLETED' THEN
    INSERT INTO completion_ledger(user_id, attempt_id, points) VALUES (NEW.user_id, NEW.attempt_id, NEW.earned_points);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER attempt_completion AFTER INSERT OR UPDATE ON lesson_attempts FOR EACH ROW EXECUTE FUNCTION write_completion_ledger();
CREATE FUNCTION validate_completion_ledger() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM lesson_attempts WHERE user_id = NEW.user_id AND attempt_id = NEW.attempt_id
    AND status = 'COMPLETED' AND earned_points = NEW.points) THEN
    RAISE EXCEPTION 'Ledger must match an accepted completed attempt' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ledger_validation BEFORE INSERT ON completion_ledger FOR EACH ROW EXECUTE FUNCTION validate_completion_ledger();

CREATE FUNCTION reject_history_change() RETURNS trigger LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
BEGIN RAISE EXCEPTION 'Retained history cannot be updated/deleted' USING ERRCODE = '23514'; END $$;
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['completion_ledger','sync_changes','sync_mutation_receipts','user_consents'] LOOP
    EXECUTE format('CREATE TRIGGER retain_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_history_change()', tbl);
  END LOOP;
  FOREACH tbl IN ARRAY ARRAY['lesson_attempts','favorites','personal_collections','user_preferences'] LOOP
    EXECUTE format('CREATE TRIGGER retain_sync_entity BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_history_change()', tbl);
  END LOOP;
END $$;

-- Derived data replaces duplicated mutable counters and the old learning-event graph.
CREATE VIEW user_total_points AS
  SELECT u.id AS user_id, coalesce(sum(l.points), 0)::bigint AS total_points
  FROM app_users u LEFT JOIN completion_ledger l ON l.user_id = u.id GROUP BY u.id;
CREATE VIEW user_completed_lessons AS
  SELECT DISTINCT user_id, lesson_id FROM lesson_attempts WHERE status = 'COMPLETED';
CREATE VIEW user_learned_signs AS
  SELECT DISTINCT a.user_id, s.sign_id FROM lesson_attempts a
  JOIN lesson_version_signs s ON s.lesson_version_id = a.lesson_version_id WHERE a.status = 'COMPLETED';
CREATE VIEW user_activity_dates AS
  SELECT DISTINCT user_id, completion_local_date AS activity_date FROM lesson_attempts WHERE status = 'COMPLETED';
CREATE VIEW user_streaks AS
  WITH numbered AS (
    SELECT user_id, activity_date,
      activity_date - (row_number() OVER (PARTITION BY user_id ORDER BY activity_date))::integer AS island
    FROM user_activity_dates
  ), runs AS (
    SELECT user_id, min(activity_date) AS first_date, max(activity_date) AS last_date, count(*)::integer AS days
    FROM numbered GROUP BY user_id, island
  ), user_today AS (
    SELECT u.id, (now() AT TIME ZONE coalesce(p.time_zone, u.time_zone))::date AS today
    FROM app_users u LEFT JOIN user_preferences p ON p.user_id = u.id
  )
  SELECT u.id AS user_id, coalesce(max(r.days), 0) AS record_streak,
    coalesce(max(CASE WHEN u.today BETWEEN r.first_date AND r.last_date THEN u.today - r.first_date + 1
      WHEN r.last_date = u.today - 1 THEN r.days ELSE 0 END), 0) AS current_streak
  FROM user_today u LEFT JOIN runs r ON r.user_id = u.id GROUP BY u.id;

-- No client-writable entitlement table. Access derives from Store verification.
CREATE VIEW user_entitlements AS
  SELECT user_id, 'PREMIUM_COLLECTIONS'::text AS entitlement_code,
    CASE WHEN bool_or(expires_at IS NULL) THEN NULL ELSE max(expires_at) END AS valid_until
  FROM subscriptions
  WHERE status IN ('ACTIVE','GRACE_PERIOD') AND verified_at IS NOT NULL
    AND valid_from <= now() AND (expires_at IS NULL OR expires_at > now())
  GROUP BY user_id;

INSERT INTO app_settings(setting_key, setting_value) VALUES
  ('mobile_policy', '{"apiVersion":"v1","contractVersion":"0.2.0","dailyLessonLimit":null,"practiceMinimumDistinctLessons":3,"practiceModes":["QUIZ","FLASHCARDS","SIGN_MATCHING","ALPHABET_MATCHING"],"offlineLearningSupported":false}'),
  ('scoring_policy', '{"version":1,"ordinaryQuestions":"FIRST_ATTEMPT_ONLY","matching":"EACH_CORRECT_PAIR_ONCE","reviewPoints":0,"commitOn":"LESSON_COMPLETED","replayEarnsPoints":true}');
-- Supply supportUrl, actual Store products and real content through deployment
-- configuration/import. Do not seed fake Store SKUs, prices or Premium grants.
