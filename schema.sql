--
-- PostgreSQL database dump
--

\restrict niI6htLDgddP0o2QtWCUPGpkg1lqWZZM9FeNa4KDp79isUCx3ghHHPifBYP8xuI

-- Dumped from database version 18.6 (fd34222)
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: asl; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA asl;


--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA public;


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: assign_sync_sequence(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.assign_sync_sequence() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: check_account_time_zone(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.check_account_time_zone() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN PERFORM validate_time_zone(NEW.time_zone); RETURN NEW; END $$;


--
-- Name: check_entity_revision(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.check_entity_revision() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: protect_lesson_version(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.protect_lesson_version() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: protect_published_media(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.protect_published_media() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: protect_purchase_owner(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.protect_purchase_owner() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN
  IF NEW.user_id <> OLD.user_id OR NEW.store <> OLD.store THEN
    RAISE EXCEPTION 'Store ownership cannot be transferred by restore' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;


--
-- Name: protect_version_child(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.protect_version_child() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: reject_history_change(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.reject_history_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN RAISE EXCEPTION 'Retained history cannot be updated/deleted' USING ERRCODE = '23514'; END $$;


--
-- Name: retain_catalog_identity(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.retain_catalog_identity() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN
  IF NEW.id <> OLD.id THEN RAISE EXCEPTION 'Catalog identity cannot change' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;


--
-- Name: retain_sign_id(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.retain_sign_id() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN RAISE EXCEPTION 'Retire signs; retain IDs referenced by sync and content' USING ERRCODE = '23514'; END $$;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END $$;


--
-- Name: validate_completion_ledger(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.validate_completion_ledger() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM lesson_attempts WHERE user_id = NEW.user_id AND attempt_id = NEW.attempt_id
    AND status = 'COMPLETED' AND earned_points = NEW.points) THEN
    RAISE EXCEPTION 'Ledger must match an accepted completed attempt' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;


--
-- Name: validate_lesson_attempt(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.validate_lesson_attempt() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: validate_personal_collection(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.validate_personal_collection() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
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


--
-- Name: validate_report_target(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.validate_report_target() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
DECLARE valid boolean;
BEGIN
  CASE NEW.target_type
    WHEN 'SIGN' THEN SELECT EXISTS(SELECT 1 FROM signs WHERE id = NEW.target_id) INTO valid;
    WHEN 'LESSON' THEN SELECT EXISTS(SELECT 1 FROM lessons WHERE id = NEW.target_id) INTO valid;
    WHEN 'COLLECTION' THEN SELECT EXISTS(SELECT 1 FROM collections WHERE id = NEW.target_id) INTO valid;
    WHEN 'EXERCISE' THEN SELECT EXISTS(SELECT 1 FROM lesson_exercises WHERE id = NEW.target_id AND lesson_version_id = NEW.lesson_version_id) INTO valid;
    WHEN 'PRACTICE_ITEM' THEN
      valid := coalesce(NEW.practice_context->'item'->>'id' = NEW.target_id::text, false);
    ELSE valid := false;
  END CASE;
  IF NOT valid THEN RAISE EXCEPTION 'Invalid report target/version' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;


--
-- Name: validate_time_zone(text); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.validate_time_zone(p_zone text) RETURNS void
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = p_zone) THEN
    RAISE EXCEPTION 'Unknown IANA time zone: %', p_zone USING ERRCODE = '23514';
  END IF;
END $$;


--
-- Name: write_completion_ledger(); Type: FUNCTION; Schema: asl; Owner: -
--

CREATE FUNCTION asl.write_completion_ledger() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'asl', 'pg_temp'
    AS $$
BEGIN
  IF NEW.status = 'COMPLETED' THEN
    INSERT INTO completion_ledger(user_id, attempt_id, points) VALUES (NEW.user_id, NEW.attempt_id, NEW.earned_points);
  END IF;
  RETURN NEW;
END $$;


SET default_table_access_method = heap;

--
-- Name: api_request_receipts; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.api_request_receipts (
    scope_key text NOT NULL,
    client_request_id uuid NOT NULL,
    request_hash bytea NOT NULL,
    response_secret_ref text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT api_request_receipts_check CHECK ((expires_at > created_at)),
    CONSTRAINT api_request_receipts_request_hash_check CHECK ((octet_length(request_hash) = 32))
);


--
-- Name: app_settings; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.app_settings (
    setting_key text NOT NULL,
    setting_value jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: app_users; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.app_users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email text NOT NULL,
    display_name character varying(100),
    time_zone text DEFAULT 'UTC'::text NOT NULL,
    suspended_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT app_users_email_check CHECK (((email = btrim(email)) AND (length(email) > 0)))
);


--
-- Name: auth_otp_challenges; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.auth_otp_challenges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email text NOT NULL,
    otp_hash text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    attempt_limit integer NOT NULL,
    resend_count integer DEFAULT 0 NOT NULL,
    resend_after timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT auth_otp_challenges_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT auth_otp_challenges_attempt_limit_check CHECK ((attempt_limit > 0)),
    CONSTRAINT auth_otp_challenges_check CHECK ((expires_at > created_at)),
    CONSTRAINT auth_otp_challenges_resend_count_check CHECK ((resend_count >= 0))
);


--
-- Name: auth_refresh_sessions; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.auth_refresh_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    refresh_token_hash text NOT NULL,
    device_label character varying(120),
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone,
    CONSTRAINT auth_refresh_sessions_check CHECK ((expires_at > created_at))
);


--
-- Name: collection_signs; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.collection_signs (
    collection_id uuid NOT NULL,
    sign_id uuid NOT NULL,
    "position" integer NOT NULL,
    CONSTRAINT collection_signs_position_check CHECK (("position" >= 0))
);


--
-- Name: collections; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.collections (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    search_name text GENERATED ALWAYS AS (lower(NORMALIZE(btrim(name), NFC))) STORED,
    description character varying(2000) DEFAULT ''::character varying NOT NULL,
    cover_asset_id uuid,
    is_premium boolean DEFAULT false NOT NULL,
    published_at timestamp with time zone,
    retired_at timestamp with time zone,
    CONSTRAINT collections_name_check CHECK ((length(btrim(name)) > 0))
);


--
-- Name: completion_ledger; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.completion_ledger (
    user_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    points integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT completion_ledger_points_check CHECK ((points >= 0))
);


--
-- Name: conversation_turns; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.conversation_turns (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lesson_version_id uuid NOT NULL,
    "position" integer NOT NULL,
    speaker_side text NOT NULL,
    gloss_caption text NOT NULL,
    english_translation text NOT NULL,
    video_asset_id uuid NOT NULL,
    CONSTRAINT conversation_turns_position_check CHECK (("position" >= 0)),
    CONSTRAINT conversation_turns_speaker_side_check CHECK ((speaker_side = ANY (ARRAY['PARTNER'::text, 'LEARNER'::text])))
);


--
-- Name: exercise_signs; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.exercise_signs (
    lesson_version_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    sign_id uuid NOT NULL
);


--
-- Name: favorites; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.favorites (
    user_id uuid NOT NULL,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    sign_id uuid,
    collection_id uuid,
    is_favorite boolean NOT NULL,
    revision bigint NOT NULL,
    CONSTRAINT favorites_check CHECK ((((target_type = 'SIGN'::text) AND (sign_id IS NOT NULL) AND (sign_id = target_id) AND (collection_id IS NULL)) OR ((target_type = 'COLLECTION'::text) AND (collection_id IS NOT NULL) AND (collection_id = target_id) AND (sign_id IS NULL)))),
    CONSTRAINT favorites_revision_check CHECK ((revision > 0)),
    CONSTRAINT favorites_target_type_check CHECK ((target_type = ANY (ARRAY['SIGN'::text, 'COLLECTION'::text])))
);


--
-- Name: legal_documents; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.legal_documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    document_type text NOT NULL,
    version text NOT NULL,
    public_url text NOT NULL,
    published_at timestamp with time zone NOT NULL,
    CONSTRAINT legal_documents_document_type_check CHECK ((document_type = ANY (ARRAY['TERMS'::text, 'PRIVACY'::text, 'ANALYTICS_CONSENT'::text])))
);


--
-- Name: lesson_attempts; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_attempts (
    user_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    lesson_version_id uuid NOT NULL,
    status text NOT NULL,
    started_at timestamp with time zone NOT NULL,
    client_updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    completion_time_zone text NOT NULL,
    completion_local_date date,
    checkpoint jsonb NOT NULL,
    max_points integer NOT NULL,
    earned_points integer NOT NULL,
    score_percent numeric(5,2) GENERATED ALWAYS AS (
CASE
    WHEN ((status = 'COMPLETED'::text) AND (max_points > 0)) THEN round(((100.0 * (earned_points)::numeric) / (max_points)::numeric), 2)
    ELSE NULL::numeric
END) STORED,
    revision bigint NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lesson_attempts_check CHECK (((earned_points >= 0) AND (earned_points <= max_points))),
    CONSTRAINT lesson_attempts_check1 CHECK ((started_at <= client_updated_at)),
    CONSTRAINT lesson_attempts_check2 CHECK ((((status = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND (completion_local_date IS NOT NULL) AND ((completed_at >= started_at) AND (completed_at <= client_updated_at))) OR ((status <> 'COMPLETED'::text) AND (completed_at IS NULL) AND (completion_local_date IS NULL) AND (earned_points = 0)))),
    CONSTRAINT lesson_attempts_checkpoint_check CHECK ((jsonb_typeof(checkpoint) = 'object'::text)),
    CONSTRAINT lesson_attempts_max_points_check CHECK ((max_points >= 0)),
    CONSTRAINT lesson_attempts_revision_check CHECK ((revision > 0)),
    CONSTRAINT lesson_attempts_status_check CHECK ((status = ANY (ARRAY['IN_PROGRESS'::text, 'COMPLETED'::text, 'ABANDONED'::text])))
);


--
-- Name: lesson_exercise_media; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_exercise_media (
    lesson_version_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    slot_key text NOT NULL,
    media_asset_id uuid NOT NULL,
    CONSTRAINT lesson_exercise_media_slot_key_check CHECK ((slot_key ~ '^[A-Za-z0-9_-]{1,64}$'::text))
);


--
-- Name: lesson_exercises; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_exercises (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lesson_version_id uuid NOT NULL,
    "position" integer NOT NULL,
    exercise_type text NOT NULL,
    prompt text,
    payload jsonb NOT NULL,
    answer_key jsonb,
    points_per_correct integer NOT NULL,
    CONSTRAINT lesson_exercises_answer_key_check CHECK ((jsonb_typeof(answer_key) = 'object'::text)),
    CONSTRAINT lesson_exercises_check CHECK ((((exercise_type = ANY (ARRAY['TIP'::text, 'ENCOURAGEMENT'::text, 'REVIEW_INTRO'::text, 'PRAISE'::text])) AND (answer_key IS NULL) AND (points_per_correct = 0)) OR ((exercise_type <> ALL (ARRAY['TIP'::text, 'ENCOURAGEMENT'::text, 'REVIEW_INTRO'::text, 'PRAISE'::text])) AND (answer_key IS NOT NULL) AND (answer_key <> '{}'::jsonb)))),
    CONSTRAINT lesson_exercises_exercise_type_check CHECK ((exercise_type = ANY (ARRAY['VIDEO_TO_WORD'::text, 'WORD_TO_VIDEO'::text, 'VIDEO_TO_IMAGE'::text, 'MATCH_PAIRS'::text, 'FILL_GAP'::text, 'ORDER_SIGNS'::text, 'VIDEO_TO_MEANING'::text, 'KNOWLEDGE'::text, 'SPELL'::text, 'TIP'::text, 'ENCOURAGEMENT'::text, 'REVIEW_INTRO'::text, 'PRAISE'::text]))),
    CONSTRAINT lesson_exercises_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT lesson_exercises_points_per_correct_check CHECK (((points_per_correct >= 0) AND (points_per_correct <= 1000))),
    CONSTRAINT lesson_exercises_position_check CHECK (("position" >= 0))
);


--
-- Name: lesson_prerequisites; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_prerequisites (
    lesson_id uuid NOT NULL,
    prerequisite_lesson_id uuid NOT NULL,
    CONSTRAINT lesson_prerequisites_check CHECK ((lesson_id <> prerequisite_lesson_id))
);


--
-- Name: lesson_version_signs; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_version_signs (
    lesson_version_id uuid NOT NULL,
    sign_id uuid NOT NULL,
    name_snapshot character varying(200) NOT NULL,
    kind_snapshot text NOT NULL,
    video_asset_id uuid NOT NULL,
    "position" integer NOT NULL,
    CONSTRAINT lesson_version_signs_kind_snapshot_check CHECK ((kind_snapshot = ANY (ARRAY['LETTER'::text, 'NUMBER'::text, 'WORD'::text, 'PHRASE'::text, 'SENTENCE'::text]))),
    CONSTRAINT lesson_version_signs_position_check CHECK (("position" >= 0))
);


--
-- Name: lesson_versions; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lesson_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lesson_id uuid NOT NULL,
    version_no integer NOT NULL,
    title text NOT NULL,
    lesson_type text NOT NULL,
    scoring_policy_version integer DEFAULT 1 NOT NULL,
    published_at timestamp with time zone,
    is_current boolean DEFAULT false NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lesson_versions_check CHECK (((NOT is_current) OR ((published_at IS NOT NULL) AND (revoked_at IS NULL)))),
    CONSTRAINT lesson_versions_check1 CHECK (((revoked_at IS NULL) OR (published_at IS NOT NULL))),
    CONSTRAINT lesson_versions_lesson_type_check CHECK ((lesson_type = ANY (ARRAY['LESSON'::text, 'ALPHABET'::text, 'REVIEW'::text, 'CONVERSATION'::text]))),
    CONSTRAINT lesson_versions_scoring_policy_version_check CHECK ((scoring_policy_version = 1)),
    CONSTRAINT lesson_versions_version_no_check CHECK ((version_no > 0))
);


--
-- Name: lessons; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.lessons (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    unit_id uuid NOT NULL,
    "position" integer NOT NULL,
    retired_at timestamp with time zone,
    CONSTRAINT lessons_position_check CHECK (("position" >= 0))
);


--
-- Name: media_assets; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.media_assets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    storage_key text NOT NULL,
    mime_type text NOT NULL,
    duration_ms integer,
    sha256_checksum text NOT NULL,
    rights_reference text NOT NULL,
    published_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT media_assets_duration_ms_check CHECK ((duration_ms >= 0)),
    CONSTRAINT media_assets_mime_type_check CHECK ((mime_type = ANY (ARRAY['video/mp4'::text, 'image/webp'::text, 'image/png'::text]))),
    CONSTRAINT media_assets_rights_reference_check CHECK ((length(btrim(rights_reference)) > 0)),
    CONSTRAINT media_assets_sha256_checksum_check CHECK ((sha256_checksum ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: personal_collections; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.personal_collections (
    user_id uuid NOT NULL,
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    sign_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    revision bigint NOT NULL,
    CONSTRAINT personal_collections_check CHECK (((NOT deleted) OR (cardinality(sign_ids) = 0))),
    CONSTRAINT personal_collections_name_check CHECK ((length((name)::text) > 0)),
    CONSTRAINT personal_collections_revision_check CHECK ((revision > 0)),
    CONSTRAINT personal_collections_sign_ids_check CHECK ((cardinality(sign_ids) <= 1000))
);


--
-- Name: signs; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.signs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(200) NOT NULL,
    search_name text GENERATED ALWAYS AS (lower(NORMALIZE(btrim((name)::text), NFC))) STORED,
    content_kind text NOT NULL,
    video_asset_id uuid NOT NULL,
    published_at timestamp with time zone,
    retired_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT signs_content_kind_check CHECK ((content_kind = ANY (ARRAY['LETTER'::text, 'NUMBER'::text, 'WORD'::text, 'PHRASE'::text, 'SENTENCE'::text]))),
    CONSTRAINT signs_name_check CHECK ((length(btrim((name)::text)) > 0))
);


--
-- Name: store_products; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.store_products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    store text NOT NULL,
    external_product_id text NOT NULL,
    base_plan_id text,
    period text NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT store_products_check CHECK (((store <> 'APP_STORE'::text) OR (base_plan_id IS NULL))),
    CONSTRAINT store_products_period_check CHECK ((period = ANY (ARRAY['MONTHLY'::text, 'QUARTERLY'::text, 'YEARLY'::text]))),
    CONSTRAINT store_products_store_check CHECK ((store = ANY (ARRAY['APP_STORE'::text, 'GOOGLE_PLAY'::text])))
);


--
-- Name: store_purchases; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.store_purchases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    store text NOT NULL,
    requested_product_id text NOT NULL,
    store_product_id uuid,
    subscription_id uuid,
    proof_hash bytea NOT NULL,
    proof_secret_ref text NOT NULL,
    external_transaction_id text,
    purchase_token_hash text,
    status text DEFAULT 'PENDING'::text NOT NULL,
    reason text,
    verified_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT store_purchases_check CHECK (((status <> 'VERIFIED'::text) OR ((verified_at IS NOT NULL) AND (subscription_id IS NOT NULL) AND (store_product_id IS NOT NULL)))),
    CONSTRAINT store_purchases_check1 CHECK (((status <> 'VERIFIED'::text) OR ((store = 'APP_STORE'::text) AND (external_transaction_id IS NOT NULL)) OR ((store = 'GOOGLE_PLAY'::text) AND (purchase_token_hash IS NOT NULL)))),
    CONSTRAINT store_purchases_proof_hash_check CHECK ((octet_length(proof_hash) = 32)),
    CONSTRAINT store_purchases_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'VERIFIED'::text, 'REJECTED'::text]))),
    CONSTRAINT store_purchases_store_check CHECK ((store = ANY (ARRAY['APP_STORE'::text, 'GOOGLE_PLAY'::text])))
);


--
-- Name: store_webhook_events; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.store_webhook_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    store text NOT NULL,
    external_event_id text NOT NULL,
    payload_secret_ref text NOT NULL,
    processing_status text DEFAULT 'PENDING'::text NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT store_webhook_events_processing_status_check CHECK ((processing_status = ANY (ARRAY['PENDING'::text, 'PROCESSED'::text, 'FAILED'::text]))),
    CONSTRAINT store_webhook_events_store_check CHECK ((store = ANY (ARRAY['APP_STORE'::text, 'GOOGLE_PLAY'::text])))
);


--
-- Name: subscriptions; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    store text NOT NULL,
    store_product_id uuid NOT NULL,
    external_subscription_key text NOT NULL,
    status text NOT NULL,
    valid_from timestamp with time zone,
    expires_at timestamp with time zone,
    auto_renew boolean DEFAULT false NOT NULL,
    verified_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT subscriptions_check CHECK (((expires_at IS NULL) OR ((valid_from IS NOT NULL) AND (expires_at > valid_from)))),
    CONSTRAINT subscriptions_check1 CHECK (((status <> ALL (ARRAY['ACTIVE'::text, 'GRACE_PERIOD'::text])) OR ((verified_at IS NOT NULL) AND (valid_from IS NOT NULL)))),
    CONSTRAINT subscriptions_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'GRACE_PERIOD'::text, 'EXPIRED'::text, 'REVOKED'::text, 'PENDING'::text]))),
    CONSTRAINT subscriptions_store_check CHECK ((store = ANY (ARRAY['APP_STORE'::text, 'GOOGLE_PLAY'::text])))
);


--
-- Name: sync_changes; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.sync_changes (
    user_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    kind text NOT NULL,
    entity_key text NOT NULL,
    revision bigint NOT NULL,
    data jsonb NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sync_changes_data_check CHECK ((jsonb_typeof(data) = 'object'::text)),
    CONSTRAINT sync_changes_kind_check CHECK ((kind = ANY (ARRAY['LESSON_ATTEMPT'::text, 'FAVORITE'::text, 'PERSONAL_COLLECTION'::text, 'PREFERENCES'::text]))),
    CONSTRAINT sync_changes_revision_check CHECK ((revision > 0))
);


--
-- Name: sync_mutation_receipts; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.sync_mutation_receipts (
    user_id uuid NOT NULL,
    mutation_id uuid NOT NULL,
    mutation_body jsonb NOT NULL,
    change_sequence bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sync_mutation_receipts_mutation_body_check CHECK ((jsonb_typeof(mutation_body) = 'object'::text))
);


--
-- Name: sync_streams; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.sync_streams (
    user_id uuid NOT NULL,
    last_sequence bigint DEFAULT 0 NOT NULL,
    CONSTRAINT sync_streams_last_sequence_check CHECK ((last_sequence >= 0))
);


--
-- Name: units; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.units (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title text NOT NULL,
    "position" integer NOT NULL,
    published_at timestamp with time zone,
    retired_at timestamp with time zone,
    CONSTRAINT units_position_check CHECK (("position" >= 0))
);


--
-- Name: user_activity_dates; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_activity_dates AS
 SELECT DISTINCT user_id,
    completion_local_date AS activity_date
   FROM asl.lesson_attempts
  WHERE (status = 'COMPLETED'::text);


--
-- Name: user_completed_lessons; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_completed_lessons AS
 SELECT DISTINCT user_id,
    lesson_id
   FROM asl.lesson_attempts
  WHERE (status = 'COMPLETED'::text);


--
-- Name: user_consents; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.user_consents (
    user_id uuid NOT NULL,
    document_id uuid NOT NULL,
    accepted_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: user_entitlements; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_entitlements AS
 SELECT user_id,
    'PREMIUM_COLLECTIONS'::text AS entitlement_code,
        CASE
            WHEN bool_or((expires_at IS NULL)) THEN NULL::timestamp with time zone
            ELSE max(expires_at)
        END AS valid_until
   FROM asl.subscriptions
  WHERE ((status = ANY (ARRAY['ACTIVE'::text, 'GRACE_PERIOD'::text])) AND (verified_at IS NOT NULL) AND (valid_from <= now()) AND ((expires_at IS NULL) OR (expires_at > now())))
  GROUP BY user_id;


--
-- Name: user_learned_signs; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_learned_signs AS
 SELECT DISTINCT a.user_id,
    s.sign_id
   FROM (asl.lesson_attempts a
     JOIN asl.lesson_version_signs s ON ((s.lesson_version_id = a.lesson_version_id)))
  WHERE (a.status = 'COMPLETED'::text);


--
-- Name: user_preferences; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.user_preferences (
    user_id uuid NOT NULL,
    haptics_enabled boolean NOT NULL,
    daily_reminder_enabled boolean NOT NULL,
    daily_reminder_local_time text,
    learning_goal_days smallint NOT NULL,
    time_zone text NOT NULL,
    revision bigint NOT NULL,
    CONSTRAINT user_preferences_daily_reminder_local_time_check CHECK ((daily_reminder_local_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'::text)),
    CONSTRAINT user_preferences_learning_goal_days_check CHECK ((learning_goal_days = ANY (ARRAY[7, 14, 30, 50]))),
    CONSTRAINT user_preferences_revision_check CHECK ((revision > 0))
);


--
-- Name: user_reports; Type: TABLE; Schema: asl; Owner: -
--

CREATE TABLE asl.user_reports (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    client_report_id uuid NOT NULL,
    user_id uuid,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    lesson_version_id uuid,
    reason text NOT NULL,
    message character varying(2000) NOT NULL,
    request_hash bytea NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    practice_context jsonb,
    CONSTRAINT user_reports_check CHECK (((target_type = 'EXERCISE'::text) = (lesson_version_id IS NOT NULL))),
    CONSTRAINT user_reports_message_check CHECK ((length((message)::text) > 0)),
    CONSTRAINT user_reports_practice_context_check CHECK (
CASE
    WHEN (target_type = 'PRACTICE_ITEM'::text) THEN COALESCE(((jsonb_typeof(practice_context) = 'object'::text) AND (practice_context ?& ARRAY['setId'::text, 'mode'::text, 'sourceLessons'::text, 'generatedAt'::text, 'item'::text]) AND ((practice_context ->> 'mode'::text) = 'QUIZ'::text) AND (jsonb_typeof((practice_context -> 'setId'::text)) = 'string'::text) AND (jsonb_typeof((practice_context -> 'generatedAt'::text)) = 'string'::text) AND (jsonb_typeof((practice_context -> 'sourceLessons'::text)) = 'array'::text) AND (jsonb_typeof((practice_context -> 'item'::text)) = 'object'::text) AND ((practice_context -> 'item'::text) ?& ARRAY['id'::text, 'signId'::text, 'type'::text, 'payload'::text, 'answerKey'::text]) AND (((practice_context -> 'item'::text) ->> 'id'::text) = (target_id)::text) AND (((practice_context -> 'item'::text) ->> 'type'::text) = ANY (ARRAY['VIDEO_TO_WORD'::text, 'WORD_TO_VIDEO'::text, 'VIDEO_TO_IMAGE'::text])) AND (jsonb_typeof(((practice_context -> 'item'::text) -> 'payload'::text)) = 'object'::text) AND (jsonb_typeof(((practice_context -> 'item'::text) -> 'answerKey'::text)) = 'object'::text)), false)
    ELSE (practice_context IS NULL)
END),
    CONSTRAINT user_reports_reason_check CHECK ((reason = ANY (ARRAY['WRONG_CONTENT'::text, 'VIDEO_PROBLEM'::text, 'WRONG_ANSWER'::text, 'OTHER'::text]))),
    CONSTRAINT user_reports_request_hash_check CHECK ((octet_length(request_hash) = 32)),
    CONSTRAINT user_reports_target_type_check CHECK ((target_type = ANY (ARRAY['SIGN'::text, 'LESSON'::text, 'EXERCISE'::text, 'COLLECTION'::text, 'PRACTICE_ITEM'::text])))
);


--
-- Name: COLUMN user_reports.practice_context; Type: COMMENT; Schema: asl; Owner: -
--

COMMENT ON COLUMN asl.user_reports.practice_context IS 'Client-held Quiz snapshot. Application validates OpenAPI shape, source/version and option/answer references; never proof of issuance. Retain even if media URLs expire.';


--
-- Name: user_streaks; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_streaks AS
 WITH numbered AS (
         SELECT user_activity_dates.user_id,
            user_activity_dates.activity_date,
            (user_activity_dates.activity_date - (row_number() OVER (PARTITION BY user_activity_dates.user_id ORDER BY user_activity_dates.activity_date))::integer) AS island
           FROM asl.user_activity_dates
        ), runs AS (
         SELECT numbered.user_id,
            min(numbered.activity_date) AS first_date,
            max(numbered.activity_date) AS last_date,
            (count(*))::integer AS days
           FROM numbered
          GROUP BY numbered.user_id, numbered.island
        ), user_today AS (
         SELECT u_1.id,
            ((now() AT TIME ZONE COALESCE(p.time_zone, u_1.time_zone)))::date AS today
           FROM (asl.app_users u_1
             LEFT JOIN asl.user_preferences p ON ((p.user_id = u_1.id)))
        )
 SELECT u.id AS user_id,
    COALESCE(max(r.days), 0) AS record_streak,
    COALESCE(max(
        CASE
            WHEN ((u.today >= r.first_date) AND (u.today <= r.last_date)) THEN ((u.today - r.first_date) + 1)
            WHEN (r.last_date = (u.today - 1)) THEN r.days
            ELSE 0
        END), 0) AS current_streak
   FROM (user_today u
     LEFT JOIN runs r ON ((r.user_id = u.id)))
  GROUP BY u.id;


--
-- Name: user_total_points; Type: VIEW; Schema: asl; Owner: -
--

CREATE VIEW asl.user_total_points AS
 SELECT u.id AS user_id,
    COALESCE(sum(l.points), (0)::bigint) AS total_points
   FROM (asl.app_users u
     LEFT JOIN asl.completion_ledger l ON ((l.user_id = u.id)))
  GROUP BY u.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: api_request_receipts api_request_receipts_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.api_request_receipts
    ADD CONSTRAINT api_request_receipts_pkey PRIMARY KEY (scope_key, client_request_id);


--
-- Name: app_settings app_settings_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.app_settings
    ADD CONSTRAINT app_settings_pkey PRIMARY KEY (setting_key);


--
-- Name: app_users app_users_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.app_users
    ADD CONSTRAINT app_users_pkey PRIMARY KEY (id);


--
-- Name: auth_otp_challenges auth_otp_challenges_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.auth_otp_challenges
    ADD CONSTRAINT auth_otp_challenges_pkey PRIMARY KEY (id);


--
-- Name: auth_refresh_sessions auth_refresh_sessions_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.auth_refresh_sessions
    ADD CONSTRAINT auth_refresh_sessions_pkey PRIMARY KEY (id);


--
-- Name: auth_refresh_sessions auth_refresh_sessions_refresh_token_hash_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.auth_refresh_sessions
    ADD CONSTRAINT auth_refresh_sessions_refresh_token_hash_key UNIQUE (refresh_token_hash);


--
-- Name: collection_signs collection_signs_collection_id_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collection_signs
    ADD CONSTRAINT collection_signs_collection_id_position_key UNIQUE (collection_id, "position");


--
-- Name: collection_signs collection_signs_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collection_signs
    ADD CONSTRAINT collection_signs_pkey PRIMARY KEY (collection_id, sign_id);


--
-- Name: collections collections_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collections
    ADD CONSTRAINT collections_pkey PRIMARY KEY (id);


--
-- Name: completion_ledger completion_ledger_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.completion_ledger
    ADD CONSTRAINT completion_ledger_pkey PRIMARY KEY (user_id, attempt_id);


--
-- Name: conversation_turns conversation_turns_lesson_version_id_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.conversation_turns
    ADD CONSTRAINT conversation_turns_lesson_version_id_position_key UNIQUE (lesson_version_id, "position");


--
-- Name: conversation_turns conversation_turns_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.conversation_turns
    ADD CONSTRAINT conversation_turns_pkey PRIMARY KEY (id);


--
-- Name: exercise_signs exercise_signs_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.exercise_signs
    ADD CONSTRAINT exercise_signs_pkey PRIMARY KEY (exercise_id, sign_id);


--
-- Name: favorites favorites_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.favorites
    ADD CONSTRAINT favorites_pkey PRIMARY KEY (user_id, target_type, target_id);


--
-- Name: legal_documents legal_documents_document_type_version_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.legal_documents
    ADD CONSTRAINT legal_documents_document_type_version_key UNIQUE (document_type, version);


--
-- Name: legal_documents legal_documents_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.legal_documents
    ADD CONSTRAINT legal_documents_pkey PRIMARY KEY (id);


--
-- Name: lesson_attempts lesson_attempts_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_attempts
    ADD CONSTRAINT lesson_attempts_pkey PRIMARY KEY (user_id, attempt_id);


--
-- Name: lesson_exercise_media lesson_exercise_media_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercise_media
    ADD CONSTRAINT lesson_exercise_media_pkey PRIMARY KEY (exercise_id, slot_key);


--
-- Name: lesson_exercises lesson_exercises_id_lesson_version_id_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercises
    ADD CONSTRAINT lesson_exercises_id_lesson_version_id_key UNIQUE (id, lesson_version_id);


--
-- Name: lesson_exercises lesson_exercises_lesson_version_id_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercises
    ADD CONSTRAINT lesson_exercises_lesson_version_id_position_key UNIQUE (lesson_version_id, "position");


--
-- Name: lesson_exercises lesson_exercises_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercises
    ADD CONSTRAINT lesson_exercises_pkey PRIMARY KEY (id);


--
-- Name: lesson_prerequisites lesson_prerequisites_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_prerequisites
    ADD CONSTRAINT lesson_prerequisites_pkey PRIMARY KEY (lesson_id, prerequisite_lesson_id);


--
-- Name: lesson_version_signs lesson_version_signs_lesson_version_id_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_version_signs
    ADD CONSTRAINT lesson_version_signs_lesson_version_id_position_key UNIQUE (lesson_version_id, "position");


--
-- Name: lesson_version_signs lesson_version_signs_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_version_signs
    ADD CONSTRAINT lesson_version_signs_pkey PRIMARY KEY (lesson_version_id, sign_id);


--
-- Name: lesson_versions lesson_versions_id_lesson_id_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_versions
    ADD CONSTRAINT lesson_versions_id_lesson_id_key UNIQUE (id, lesson_id);


--
-- Name: lesson_versions lesson_versions_lesson_id_version_no_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_versions
    ADD CONSTRAINT lesson_versions_lesson_id_version_no_key UNIQUE (lesson_id, version_no);


--
-- Name: lesson_versions lesson_versions_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_versions
    ADD CONSTRAINT lesson_versions_pkey PRIMARY KEY (id);


--
-- Name: lessons lessons_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lessons
    ADD CONSTRAINT lessons_pkey PRIMARY KEY (id);


--
-- Name: lessons lessons_unit_id_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lessons
    ADD CONSTRAINT lessons_unit_id_position_key UNIQUE (unit_id, "position");


--
-- Name: media_assets media_assets_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.media_assets
    ADD CONSTRAINT media_assets_pkey PRIMARY KEY (id);


--
-- Name: media_assets media_assets_storage_key_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.media_assets
    ADD CONSTRAINT media_assets_storage_key_key UNIQUE (storage_key);


--
-- Name: personal_collections personal_collections_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.personal_collections
    ADD CONSTRAINT personal_collections_pkey PRIMARY KEY (user_id, id);


--
-- Name: signs signs_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.signs
    ADD CONSTRAINT signs_pkey PRIMARY KEY (id);


--
-- Name: store_products store_products_id_store_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_products
    ADD CONSTRAINT store_products_id_store_key UNIQUE (id, store);


--
-- Name: store_products store_products_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_products
    ADD CONSTRAINT store_products_pkey PRIMARY KEY (id);


--
-- Name: store_products store_products_store_external_product_id_base_plan_id_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_products
    ADD CONSTRAINT store_products_store_external_product_id_base_plan_id_key UNIQUE NULLS NOT DISTINCT (store, external_product_id, base_plan_id);


--
-- Name: store_purchases store_purchases_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_purchases
    ADD CONSTRAINT store_purchases_pkey PRIMARY KEY (id);


--
-- Name: store_purchases store_purchases_store_proof_hash_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_purchases
    ADD CONSTRAINT store_purchases_store_proof_hash_key UNIQUE (store, proof_hash);


--
-- Name: store_webhook_events store_webhook_events_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_webhook_events
    ADD CONSTRAINT store_webhook_events_pkey PRIMARY KEY (id);


--
-- Name: store_webhook_events store_webhook_events_store_external_event_id_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_webhook_events
    ADD CONSTRAINT store_webhook_events_store_external_event_id_key UNIQUE (store, external_event_id);


--
-- Name: subscriptions subscriptions_id_user_id_store_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.subscriptions
    ADD CONSTRAINT subscriptions_id_user_id_store_key UNIQUE (id, user_id, store);


--
-- Name: subscriptions subscriptions_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);


--
-- Name: subscriptions subscriptions_store_external_subscription_key_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.subscriptions
    ADD CONSTRAINT subscriptions_store_external_subscription_key_key UNIQUE (store, external_subscription_key);


--
-- Name: sync_changes sync_changes_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_changes
    ADD CONSTRAINT sync_changes_pkey PRIMARY KEY (user_id, sequence_no);


--
-- Name: sync_changes sync_changes_user_id_kind_entity_key_revision_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_changes
    ADD CONSTRAINT sync_changes_user_id_kind_entity_key_revision_key UNIQUE (user_id, kind, entity_key, revision);


--
-- Name: sync_mutation_receipts sync_mutation_receipts_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_mutation_receipts
    ADD CONSTRAINT sync_mutation_receipts_pkey PRIMARY KEY (user_id, mutation_id);


--
-- Name: sync_streams sync_streams_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_streams
    ADD CONSTRAINT sync_streams_pkey PRIMARY KEY (user_id);


--
-- Name: units units_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.units
    ADD CONSTRAINT units_pkey PRIMARY KEY (id);


--
-- Name: units units_position_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.units
    ADD CONSTRAINT units_position_key UNIQUE ("position");


--
-- Name: user_consents user_consents_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_consents
    ADD CONSTRAINT user_consents_pkey PRIMARY KEY (user_id, document_id);


--
-- Name: user_preferences user_preferences_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_preferences
    ADD CONSTRAINT user_preferences_pkey PRIMARY KEY (user_id);


--
-- Name: user_reports user_reports_client_report_id_key; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_reports
    ADD CONSTRAINT user_reports_client_report_id_key UNIQUE (client_report_id);


--
-- Name: user_reports user_reports_pkey; Type: CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_reports
    ADD CONSTRAINT user_reports_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: idx_attempt_completed; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_attempt_completed ON asl.lesson_attempts USING btree (user_id, lesson_id, completion_local_date) WHERE (status = 'COMPLETED'::text);


--
-- Name: idx_attempt_version; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_attempt_version ON asl.lesson_attempts USING btree (lesson_version_id);


--
-- Name: idx_collection_order; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_collection_order ON asl.collections USING btree (search_name, id) WHERE ((published_at IS NOT NULL) AND (retired_at IS NULL));


--
-- Name: idx_collection_search; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_collection_search ON asl.collections USING gin (search_name public.gin_trgm_ops);


--
-- Name: idx_otp_email_created; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_otp_email_created ON asl.auth_otp_challenges USING btree (lower(email), created_at DESC);


--
-- Name: idx_purchase_owner; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_purchase_owner ON asl.store_purchases USING btree (user_id, created_at DESC);


--
-- Name: idx_refresh_user; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_refresh_user ON asl.auth_refresh_sessions USING btree (user_id, expires_at);


--
-- Name: idx_sign_order; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_sign_order ON asl.signs USING btree (search_name, id) WHERE ((published_at IS NOT NULL) AND (retired_at IS NULL));


--
-- Name: idx_sign_search; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_sign_search ON asl.signs USING gin (search_name public.gin_trgm_ops);


--
-- Name: idx_subscription_owner; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_subscription_owner ON asl.subscriptions USING btree (user_id, status, expires_at);


--
-- Name: idx_sync_snapshot; Type: INDEX; Schema: asl; Owner: -
--

CREATE INDEX idx_sync_snapshot ON asl.sync_changes USING btree (user_id, kind, entity_key, sequence_no DESC);


--
-- Name: uq_current_lesson_version; Type: INDEX; Schema: asl; Owner: -
--

CREATE UNIQUE INDEX uq_current_lesson_version ON asl.lesson_versions USING btree (lesson_id) WHERE is_current;


--
-- Name: uq_purchase_token; Type: INDEX; Schema: asl; Owner: -
--

CREATE UNIQUE INDEX uq_purchase_token ON asl.store_purchases USING btree (store, purchase_token_hash) WHERE (purchase_token_hash IS NOT NULL);


--
-- Name: uq_purchase_transaction; Type: INDEX; Schema: asl; Owner: -
--

CREATE UNIQUE INDEX uq_purchase_transaction ON asl.store_purchases USING btree (store, external_transaction_id) WHERE (external_transaction_id IS NOT NULL);


--
-- Name: uq_users_email; Type: INDEX; Schema: asl; Owner: -
--

CREATE UNIQUE INDEX uq_users_email ON asl.app_users USING btree (lower(email));


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: lesson_attempts attempt_completion; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER attempt_completion AFTER INSERT OR UPDATE ON asl.lesson_attempts FOR EACH ROW EXECUTE FUNCTION asl.write_completion_ledger();


--
-- Name: lesson_attempts attempt_validation; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER attempt_validation BEFORE INSERT OR UPDATE ON asl.lesson_attempts FOR EACH ROW EXECUTE FUNCTION asl.validate_lesson_attempt();


--
-- Name: favorites entity_revision; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER entity_revision BEFORE INSERT OR UPDATE ON asl.favorites FOR EACH ROW EXECUTE FUNCTION asl.check_entity_revision();


--
-- Name: lesson_attempts entity_revision; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER entity_revision BEFORE INSERT OR UPDATE ON asl.lesson_attempts FOR EACH ROW EXECUTE FUNCTION asl.check_entity_revision();


--
-- Name: personal_collections entity_revision; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER entity_revision BEFORE INSERT OR UPDATE ON asl.personal_collections FOR EACH ROW EXECUTE FUNCTION asl.check_entity_revision();


--
-- Name: user_preferences entity_revision; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER entity_revision BEFORE INSERT OR UPDATE ON asl.user_preferences FOR EACH ROW EXECUTE FUNCTION asl.check_entity_revision();


--
-- Name: completion_ledger ledger_validation; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER ledger_validation BEFORE INSERT ON asl.completion_ledger FOR EACH ROW EXECUTE FUNCTION asl.validate_completion_ledger();


--
-- Name: personal_collections personal_collection_validation; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER personal_collection_validation BEFORE INSERT OR UPDATE ON asl.personal_collections FOR EACH ROW EXECUTE FUNCTION asl.validate_personal_collection();


--
-- Name: user_preferences preference_time_zone; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER preference_time_zone BEFORE INSERT OR UPDATE ON asl.user_preferences FOR EACH ROW EXECUTE FUNCTION asl.check_account_time_zone();


--
-- Name: conversation_turns protect_content; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_content BEFORE INSERT OR DELETE OR UPDATE ON asl.conversation_turns FOR EACH ROW EXECUTE FUNCTION asl.protect_version_child();


--
-- Name: exercise_signs protect_content; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_content BEFORE INSERT OR DELETE OR UPDATE ON asl.exercise_signs FOR EACH ROW EXECUTE FUNCTION asl.protect_version_child();


--
-- Name: lesson_exercise_media protect_content; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_content BEFORE INSERT OR DELETE OR UPDATE ON asl.lesson_exercise_media FOR EACH ROW EXECUTE FUNCTION asl.protect_version_child();


--
-- Name: lesson_exercises protect_content; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_content BEFORE INSERT OR DELETE OR UPDATE ON asl.lesson_exercises FOR EACH ROW EXECUTE FUNCTION asl.protect_version_child();


--
-- Name: lesson_version_signs protect_content; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_content BEFORE INSERT OR DELETE OR UPDATE ON asl.lesson_version_signs FOR EACH ROW EXECUTE FUNCTION asl.protect_version_child();


--
-- Name: media_assets protect_media; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_media BEFORE DELETE OR UPDATE ON asl.media_assets FOR EACH ROW EXECUTE FUNCTION asl.protect_published_media();


--
-- Name: lesson_versions protect_version; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER protect_version BEFORE INSERT OR DELETE OR UPDATE ON asl.lesson_versions FOR EACH ROW EXECUTE FUNCTION asl.protect_lesson_version();


--
-- Name: store_purchases purchase_owner; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER purchase_owner BEFORE UPDATE ON asl.store_purchases FOR EACH ROW EXECUTE FUNCTION asl.protect_purchase_owner();


--
-- Name: user_reports report_target; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER report_target BEFORE INSERT OR UPDATE ON asl.user_reports FOR EACH ROW EXECUTE FUNCTION asl.validate_report_target();


--
-- Name: completion_ledger retain_history; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_history BEFORE DELETE OR UPDATE ON asl.completion_ledger FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: sync_changes retain_history; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_history BEFORE DELETE OR UPDATE ON asl.sync_changes FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: sync_mutation_receipts retain_history; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_history BEFORE DELETE OR UPDATE ON asl.sync_mutation_receipts FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: user_consents retain_history; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_history BEFORE DELETE OR UPDATE ON asl.user_consents FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: signs retain_sign; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_sign BEFORE DELETE ON asl.signs FOR EACH ROW EXECUTE FUNCTION asl.retain_sign_id();


--
-- Name: favorites retain_sync_entity; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_sync_entity BEFORE DELETE ON asl.favorites FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: lesson_attempts retain_sync_entity; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_sync_entity BEFORE DELETE ON asl.lesson_attempts FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: personal_collections retain_sync_entity; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_sync_entity BEFORE DELETE ON asl.personal_collections FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: user_preferences retain_sync_entity; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER retain_sync_entity BEFORE DELETE ON asl.user_preferences FOR EACH ROW EXECUTE FUNCTION asl.reject_history_change();


--
-- Name: signs sign_identity; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER sign_identity BEFORE UPDATE ON asl.signs FOR EACH ROW EXECUTE FUNCTION asl.retain_catalog_identity();


--
-- Name: app_settings stamp_update; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER stamp_update BEFORE UPDATE ON asl.app_settings FOR EACH ROW EXECUTE FUNCTION asl.set_updated_at();


--
-- Name: app_users stamp_update; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER stamp_update BEFORE UPDATE ON asl.app_users FOR EACH ROW EXECUTE FUNCTION asl.set_updated_at();


--
-- Name: signs stamp_update; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER stamp_update BEFORE UPDATE ON asl.signs FOR EACH ROW EXECUTE FUNCTION asl.set_updated_at();


--
-- Name: subscriptions stamp_update; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER stamp_update BEFORE UPDATE ON asl.subscriptions FOR EACH ROW EXECUTE FUNCTION asl.set_updated_at();


--
-- Name: subscriptions subscription_owner; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER subscription_owner BEFORE UPDATE ON asl.subscriptions FOR EACH ROW EXECUTE FUNCTION asl.protect_purchase_owner();


--
-- Name: sync_changes sync_sequence; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER sync_sequence BEFORE INSERT ON asl.sync_changes FOR EACH ROW EXECUTE FUNCTION asl.assign_sync_sequence();


--
-- Name: app_users user_time_zone; Type: TRIGGER; Schema: asl; Owner: -
--

CREATE TRIGGER user_time_zone BEFORE INSERT OR UPDATE ON asl.app_users FOR EACH ROW EXECUTE FUNCTION asl.check_account_time_zone();


--
-- Name: auth_refresh_sessions auth_refresh_sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.auth_refresh_sessions
    ADD CONSTRAINT auth_refresh_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: collection_signs collection_signs_collection_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collection_signs
    ADD CONSTRAINT collection_signs_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES asl.collections(id);


--
-- Name: collection_signs collection_signs_sign_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collection_signs
    ADD CONSTRAINT collection_signs_sign_id_fkey FOREIGN KEY (sign_id) REFERENCES asl.signs(id);


--
-- Name: collections collections_cover_asset_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.collections
    ADD CONSTRAINT collections_cover_asset_id_fkey FOREIGN KEY (cover_asset_id) REFERENCES asl.media_assets(id);


--
-- Name: completion_ledger completion_ledger_user_id_attempt_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.completion_ledger
    ADD CONSTRAINT completion_ledger_user_id_attempt_id_fkey FOREIGN KEY (user_id, attempt_id) REFERENCES asl.lesson_attempts(user_id, attempt_id);


--
-- Name: conversation_turns conversation_turns_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.conversation_turns
    ADD CONSTRAINT conversation_turns_lesson_version_id_fkey FOREIGN KEY (lesson_version_id) REFERENCES asl.lesson_versions(id);


--
-- Name: conversation_turns conversation_turns_video_asset_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.conversation_turns
    ADD CONSTRAINT conversation_turns_video_asset_id_fkey FOREIGN KEY (video_asset_id) REFERENCES asl.media_assets(id);


--
-- Name: exercise_signs exercise_signs_exercise_id_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.exercise_signs
    ADD CONSTRAINT exercise_signs_exercise_id_lesson_version_id_fkey FOREIGN KEY (exercise_id, lesson_version_id) REFERENCES asl.lesson_exercises(id, lesson_version_id);


--
-- Name: exercise_signs exercise_signs_lesson_version_id_sign_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.exercise_signs
    ADD CONSTRAINT exercise_signs_lesson_version_id_sign_id_fkey FOREIGN KEY (lesson_version_id, sign_id) REFERENCES asl.lesson_version_signs(lesson_version_id, sign_id);


--
-- Name: favorites favorites_collection_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.favorites
    ADD CONSTRAINT favorites_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES asl.collections(id);


--
-- Name: favorites favorites_sign_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.favorites
    ADD CONSTRAINT favorites_sign_id_fkey FOREIGN KEY (sign_id) REFERENCES asl.signs(id);


--
-- Name: favorites favorites_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.favorites
    ADD CONSTRAINT favorites_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: lesson_attempts lesson_attempts_lesson_version_id_lesson_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_attempts
    ADD CONSTRAINT lesson_attempts_lesson_version_id_lesson_id_fkey FOREIGN KEY (lesson_version_id, lesson_id) REFERENCES asl.lesson_versions(id, lesson_id);


--
-- Name: lesson_attempts lesson_attempts_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_attempts
    ADD CONSTRAINT lesson_attempts_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: lesson_exercise_media lesson_exercise_media_exercise_id_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercise_media
    ADD CONSTRAINT lesson_exercise_media_exercise_id_lesson_version_id_fkey FOREIGN KEY (exercise_id, lesson_version_id) REFERENCES asl.lesson_exercises(id, lesson_version_id);


--
-- Name: lesson_exercise_media lesson_exercise_media_media_asset_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercise_media
    ADD CONSTRAINT lesson_exercise_media_media_asset_id_fkey FOREIGN KEY (media_asset_id) REFERENCES asl.media_assets(id);


--
-- Name: lesson_exercises lesson_exercises_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_exercises
    ADD CONSTRAINT lesson_exercises_lesson_version_id_fkey FOREIGN KEY (lesson_version_id) REFERENCES asl.lesson_versions(id);


--
-- Name: lesson_prerequisites lesson_prerequisites_lesson_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_prerequisites
    ADD CONSTRAINT lesson_prerequisites_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES asl.lessons(id);


--
-- Name: lesson_prerequisites lesson_prerequisites_prerequisite_lesson_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_prerequisites
    ADD CONSTRAINT lesson_prerequisites_prerequisite_lesson_id_fkey FOREIGN KEY (prerequisite_lesson_id) REFERENCES asl.lessons(id);


--
-- Name: lesson_version_signs lesson_version_signs_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_version_signs
    ADD CONSTRAINT lesson_version_signs_lesson_version_id_fkey FOREIGN KEY (lesson_version_id) REFERENCES asl.lesson_versions(id);


--
-- Name: lesson_version_signs lesson_version_signs_sign_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_version_signs
    ADD CONSTRAINT lesson_version_signs_sign_id_fkey FOREIGN KEY (sign_id) REFERENCES asl.signs(id);


--
-- Name: lesson_version_signs lesson_version_signs_video_asset_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_version_signs
    ADD CONSTRAINT lesson_version_signs_video_asset_id_fkey FOREIGN KEY (video_asset_id) REFERENCES asl.media_assets(id);


--
-- Name: lesson_versions lesson_versions_lesson_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lesson_versions
    ADD CONSTRAINT lesson_versions_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES asl.lessons(id);


--
-- Name: lessons lessons_unit_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.lessons
    ADD CONSTRAINT lessons_unit_id_fkey FOREIGN KEY (unit_id) REFERENCES asl.units(id);


--
-- Name: personal_collections personal_collections_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.personal_collections
    ADD CONSTRAINT personal_collections_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: signs signs_video_asset_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.signs
    ADD CONSTRAINT signs_video_asset_id_fkey FOREIGN KEY (video_asset_id) REFERENCES asl.media_assets(id);


--
-- Name: store_purchases store_purchases_store_product_id_store_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_purchases
    ADD CONSTRAINT store_purchases_store_product_id_store_fkey FOREIGN KEY (store_product_id, store) REFERENCES asl.store_products(id, store);


--
-- Name: store_purchases store_purchases_subscription_id_user_id_store_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_purchases
    ADD CONSTRAINT store_purchases_subscription_id_user_id_store_fkey FOREIGN KEY (subscription_id, user_id, store) REFERENCES asl.subscriptions(id, user_id, store);


--
-- Name: store_purchases store_purchases_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.store_purchases
    ADD CONSTRAINT store_purchases_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: subscriptions subscriptions_store_product_id_store_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.subscriptions
    ADD CONSTRAINT subscriptions_store_product_id_store_fkey FOREIGN KEY (store_product_id, store) REFERENCES asl.store_products(id, store);


--
-- Name: subscriptions subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.subscriptions
    ADD CONSTRAINT subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: sync_changes sync_changes_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_changes
    ADD CONSTRAINT sync_changes_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.sync_streams(user_id);


--
-- Name: sync_mutation_receipts sync_mutation_receipts_user_id_change_sequence_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_mutation_receipts
    ADD CONSTRAINT sync_mutation_receipts_user_id_change_sequence_fkey FOREIGN KEY (user_id, change_sequence) REFERENCES asl.sync_changes(user_id, sequence_no);


--
-- Name: sync_streams sync_streams_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.sync_streams
    ADD CONSTRAINT sync_streams_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: user_consents user_consents_document_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_consents
    ADD CONSTRAINT user_consents_document_id_fkey FOREIGN KEY (document_id) REFERENCES asl.legal_documents(id);


--
-- Name: user_consents user_consents_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_consents
    ADD CONSTRAINT user_consents_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: user_preferences user_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_preferences
    ADD CONSTRAINT user_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- Name: user_reports user_reports_lesson_version_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_reports
    ADD CONSTRAINT user_reports_lesson_version_id_fkey FOREIGN KEY (lesson_version_id) REFERENCES asl.lesson_versions(id);


--
-- Name: user_reports user_reports_user_id_fkey; Type: FK CONSTRAINT; Schema: asl; Owner: -
--

ALTER TABLE ONLY asl.user_reports
    ADD CONSTRAINT user_reports_user_id_fkey FOREIGN KEY (user_id) REFERENCES asl.app_users(id);


--
-- PostgreSQL database dump complete
--

\unrestrict niI6htLDgddP0o2QtWCUPGpkg1lqWZZM9FeNa4KDp79isUCx3ghHHPifBYP8xuI

