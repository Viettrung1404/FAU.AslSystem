-- Firebase Auth identity, mobile contract 0.4.0, 2026-11-07.
-- Preserve V1-V3 and internal UUIDs/FKs. No legacy email-based identity backfill.
SET LOCAL search_path = asl, public;

ALTER TABLE app_users ADD COLUMN firebase_uid varchar(128);
ALTER TABLE app_users ADD CONSTRAINT app_users_firebase_uid_check
  CHECK (firebase_uid IS NULL OR (length(firebase_uid) > 0 AND firebase_uid = btrim(firebase_uid)));
ALTER TABLE app_users ADD CONSTRAINT uq_users_firebase_uid UNIQUE (firebase_uid);
ALTER TABLE app_users ALTER COLUMN email DROP NOT NULL;
DROP INDEX uq_users_email;

COMMENT ON COLUMN app_users.firebase_uid IS
  'Verified Firebase UID in the environment configured Firebase project; NULL for unmapped legacy accounts. Never backfill by email.';
COMMENT ON COLUMN app_users.email IS
  'Optional contact email from verified Firebase identity; may be Apple private relay. Not an account identity or merge key.';
-- auth_otp_challenges/auth_refresh_sessions are historical tables, unused by Firebase auth.
