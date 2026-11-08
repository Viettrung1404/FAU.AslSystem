SET LOCAL search_path TO asl, public;

-- Keep accepted first answers and matching pairs when a device resumes/reviews.
-- V2 already validates scoring, terminal attempts and completion ledger uniqueness.
CREATE FUNCTION retain_attempt_checkpoint_progress() RETURNS trigger
LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE previous jsonb; latest jsonb;
BEGIN
  IF NEW.client_updated_at < OLD.client_updated_at THEN
    RAISE EXCEPTION 'Attempt timestamp cannot move backwards' USING ERRCODE = '23514';
  END IF;
  FOR previous IN SELECT value FROM jsonb_array_elements(OLD.checkpoint->'firstAnswers') LOOP
    SELECT value INTO latest FROM jsonb_array_elements(NEW.checkpoint->'firstAnswers')
      WHERE value->>'exerciseId' = previous->>'exerciseId';
    IF latest IS DISTINCT FROM previous THEN
      RAISE EXCEPTION 'First answer cannot be removed or overwritten during review' USING ERRCODE = '23514';
    END IF;
  END LOOP;
  FOR previous IN SELECT value FROM jsonb_array_elements(OLD.checkpoint->'matchingProgress') LOOP
    SELECT value INTO latest FROM jsonb_array_elements(NEW.checkpoint->'matchingProgress')
      WHERE value->>'exerciseId' = previous->>'exerciseId';
    IF latest IS NULL OR NOT ((latest->'matchedPairs') @> (previous->'matchedPairs')) THEN
      RAISE EXCEPTION 'Accepted matching pairs cannot be removed' USING ERRCODE = '23514';
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER retain_checkpoint BEFORE UPDATE ON lesson_attempts
FOR EACH ROW EXECUTE FUNCTION retain_attempt_checkpoint_progress();

CREATE FUNCTION validate_attempt_answer_references() RETURNS trigger
LANGUAGE plpgsql SET search_path = asl, pg_temp AS $$
DECLARE answer jsonb; payload jsonb; token text;
BEGIN
  FOR answer IN SELECT value FROM jsonb_array_elements(NEW.checkpoint->'firstAnswers') LOOP
    SELECT e.payload INTO payload FROM lesson_exercises e
      WHERE e.id = (answer->>'exerciseId')::uuid AND e.lesson_version_id = NEW.lesson_version_id;
    IF answer->'answer'->>'type' = 'CHOICE' AND NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(payload->'options') option
      WHERE option->>'id' = answer->'answer'->>'optionId') THEN
      RAISE EXCEPTION 'Unknown answer option reference' USING ERRCODE = '23514';
    END IF;
    IF answer->'answer'->>'type' = 'TOKENS' THEN
      FOR token IN SELECT value FROM jsonb_array_elements_text(answer->'answer'->'tokenIds') LOOP
        IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(payload->'bank') tile WHERE tile->>'id' = token) THEN
          RAISE EXCEPTION 'Unknown answer token reference' USING ERRCODE = '23514';
        END IF;
      END LOOP;
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER answer_references BEFORE INSERT OR UPDATE ON lesson_attempts
FOR EACH ROW EXECUTE FUNCTION validate_attempt_answer_references();
