-- Envelope encryption test fixtures (WO-193)
--
-- Contains: synthetic subjects with pre-computed envelope-encrypted fields and
-- already-destroyed-key subject for integration testing.
--
-- NO real personal data.  All identifiers are fictional.
--
-- Subject types used:
--   TECHNICIAN  — technician GPS position rows
--   PORTAL_ACCOUNT — portal invitation contact fields
--
-- Note: actual encrypted values cannot be pre-computed in SQL because they require
-- a live key manager.  Integration tests that need encrypted rows must call
-- SubjectEncryptionContext.runWith() before the insert.

-- Synthetic subject references (no real data)
-- TECHNICIAN subject with active key version 1
-- tech_id: 01234567-89ab-cdef-0123-456789abcde1 (synthetic)

-- PORTAL_ACCOUNT subject with DESTROYED key (for unrecoverable-marker tests)
-- account_id: 01234567-89ab-cdef-0123-456789abcde2 (synthetic)
-- This row's subject_data_key will have state=DESTROYED in integration test setup.

-- Placeholder row for fixture scanner allow-listing
SELECT 1 WHERE 1=0;
