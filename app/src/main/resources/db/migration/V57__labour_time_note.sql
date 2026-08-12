-- V57: Add optional note column to labour_time_record (WO-157).
-- Additive-only migration; existing rows receive NULL (nullable column).
ALTER TABLE labour_time_record ADD COLUMN note TEXT;
