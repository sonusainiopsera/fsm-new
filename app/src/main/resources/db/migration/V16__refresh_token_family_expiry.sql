-- V16__refresh_token_family_expiry.sql
-- Adds absolute expiry timestamp to refresh_token_family so rotation can enforce
-- the 7-day family lifetime without extending it on each rotation.
-- Backfills existing rows from created_at + 7 days.

ALTER TABLE refresh_token_family ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE refresh_token_family
SET expires_at = created_at + INTERVAL '7 days';

ALTER TABLE refresh_token_family ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_rtf_expires_at ON refresh_token_family (expires_at)
    WHERE revoked_at IS NULL;
