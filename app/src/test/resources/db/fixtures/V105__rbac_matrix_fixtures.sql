-- =============================================================================
-- V105: RBAC matrix test fixtures (WO-114)
-- =============================================================================
-- Adds the ADMIN user (aaaaaaaa-...-000000000003) missing from V100 so that
-- HTTP-level RBAC matrix tests can invoke user-scoped endpoints as all five roles.
--
-- Uses the aaaaaaaa-... range to match TestJwtFactory.ADMIN_USER_ID.
-- =============================================================================

INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
    VALUES ('aaaaaaaa-0000-0000-0000-000000000003',
            'admin@example.com',
            '$2a$10$placeholder.hash.admin..........', 'Test Admin', true, 0)
    ON CONFLICT (id) DO NOTHING;
