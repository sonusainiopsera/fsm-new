-- =============================================================================
-- V113: Data classification fixtures (WO-188)
-- =============================================================================
-- Provides anonymised classification rows for integration tests.
-- All values are synthetic — no real personal data. The seed rows from V28
-- already cover the full production tier mapping; these rows add test-specific
-- entries for fixture entities and a PRIVACY_ADMIN role grant for test users.
--
-- Key IDs:
--   ADMIN_USER_ID:  aaaaaaaa-0000-0000-0000-000000000003 (from V103)
-- =============================================================================

-- Extra classification rows for test assertions (beyond V28 seed)
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('dc000000-0000-7000-8000-000000000099', 'test', 'TestEntity', NULL,
     'INTERNAL', 'Test fixture entity', 'Integration test use only')
ON CONFLICT DO NOTHING;

-- Grant PRIVACY_ADMIN role to the test ADMIN user so integration tests can call privacy endpoints
INSERT INTO role_assignment (id, user_id, role_name, granted_by)
VALUES
    ('dc000000-0000-7000-8000-f00000000001',
     'aaaaaaaa-0000-0000-0000-000000000003',
     'PRIVACY_ADMIN',
     NULL)
ON CONFLICT (user_id, role_name) DO NOTHING;
