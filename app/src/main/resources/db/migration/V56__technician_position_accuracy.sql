-- V56__technician_position_accuracy.sql
-- Adds accuracy_metres and retain_until to technician_position.
-- Existing rows receive safe defaults so the migration is backwards-compatible.

ALTER TABLE technician_position
    ADD COLUMN IF NOT EXISTS accuracy_metres INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS retain_until    DATE    NOT NULL DEFAULT (CURRENT_DATE + INTERVAL '90 days');
