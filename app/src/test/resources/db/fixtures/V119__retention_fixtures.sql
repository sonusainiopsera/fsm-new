-- V119__retention_fixtures.sql
-- Test fixtures for retention policy and purge sweep tests (WO-189).
--
-- Adds two additional retention_policy test rows on top of the five seed rows
-- inserted by V35:
--   - LOCATION_TRACES_TEST: ratified=true, enabled=true, no legal hold
--     (represents an active policy ready for live purge in tests)
--   - LEGAL_HOLD_EXAMPLE: legal_hold=true (should be skipped by sweep)
--   - UNRATIFIED_EXAMPLE: ratified=false (should be skipped by sweep)
--
-- All fixture UUIDs use the dd-prefix range to avoid collisions with
-- production seed data (cc-prefix, V35) and test fixture data (aa/00-prefix).
--
-- No real personal data; all values are anonymised test markers.

INSERT INTO retention_policy (
    id, data_category, entity_name, period_value, period_unit,
    anchor_field, disposal_method, legal_hold, ratified, enabled, notes, version
) VALUES
    (
        'dd000000-0000-7000-8000-000000000001',
        'LOCATION_TRACES_TEST',
        'GpsPositionTest',
        90, 'DAYS',
        'created_at',
        'PHYSICAL_DELETE',
        false, true, true,
        'Test fixture: active policy with ratified=true, enabled=true for sweep tests.',
        0
    ),
    (
        'dd000000-0000-7000-8000-000000000002',
        'LEGAL_HOLD_EXAMPLE',
        'LegalHoldEntity',
        365, 'DAYS',
        'created_at',
        'PHYSICAL_DELETE',
        true, true, true,
        'Test fixture: legal_hold=true — should always be skipped by the purge sweep.',
        0
    ),
    (
        'dd000000-0000-7000-8000-000000000003',
        'UNRATIFIED_EXAMPLE',
        'UnratifiedEntity',
        180, 'DAYS',
        'created_at',
        'PHYSICAL_DELETE',
        false, false, true,
        'Test fixture: ratified=false — should be skipped by the purge sweep.',
        0
    )
ON CONFLICT (data_category) DO NOTHING;
