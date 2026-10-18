-- Mobile contract 0.3.0, 2026-10-19. V1/V2 checksums remain unchanged.
-- A report retains client-supplied evidence, not a server Practice history.
SET LOCAL search_path = asl, public;

ALTER TABLE user_reports DROP CONSTRAINT user_reports_target_type_check;
ALTER TABLE user_reports ADD CONSTRAINT user_reports_target_type_check
  CHECK (target_type IN ('SIGN','LESSON','EXERCISE','COLLECTION','PRACTICE_ITEM'));
ALTER TABLE user_reports ADD COLUMN practice_context jsonb;
ALTER TABLE user_reports ADD CONSTRAINT user_reports_practice_context_check CHECK (
  CASE WHEN target_type = 'PRACTICE_ITEM' THEN coalesce(
    jsonb_typeof(practice_context) = 'object'
    AND practice_context ?& ARRAY['setId','mode','sourceLessons','generatedAt','item']
    AND practice_context->>'mode' = 'QUIZ'
    AND jsonb_typeof(practice_context->'setId') = 'string'
    AND jsonb_typeof(practice_context->'generatedAt') = 'string'
    AND jsonb_typeof(practice_context->'sourceLessons') = 'array'
    AND jsonb_typeof(practice_context->'item') = 'object'
    AND practice_context->'item' ?& ARRAY['id','signId','type','payload','answerKey']
    AND practice_context->'item'->>'id' = target_id::text
    AND practice_context->'item'->>'type' IN ('VIDEO_TO_WORD','WORD_TO_VIDEO','VIDEO_TO_IMAGE')
    AND jsonb_typeof(practice_context->'item'->'payload') = 'object'
    AND jsonb_typeof(practice_context->'item'->'answerKey') = 'object', false)
  ELSE practice_context IS NULL END
);
COMMENT ON COLUMN user_reports.practice_context IS
  'Client-held Quiz snapshot. Application validates OpenAPI shape, source/version and option/answer references; never proof of issuance. Retain even if media URLs expire.';

CREATE OR REPLACE FUNCTION validate_report_target() RETURNS trigger
LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
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

UPDATE app_settings
SET setting_value = jsonb_set(setting_value, '{contractVersion}', '"0.3.0"'), updated_at = now()
WHERE setting_key = 'mobile_policy';
