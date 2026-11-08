-- Runtime identity/reporting/sync now implement the mobile 0.4 contract.
-- Keep all historical migration checksums intact.
SET LOCAL search_path = asl, public;
UPDATE app_settings
SET setting_value = jsonb_set(setting_value, '{contractVersion}', '"0.4.0"'), updated_at = now()
WHERE setting_key = 'mobile_policy';
