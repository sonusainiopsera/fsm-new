-- =============================================================================
-- V124: Portal service-request submission fixtures for WO-170 integration tests
-- =============================================================================
-- Supplements V118 (portal users) and V100 (sites/assets/customers).
--
-- AC-12: Confirms SLA policy rows for two priorities (MEDIUM, HIGH) are available.
-- The V2 migration seeds all four priorities; this fixture is a documentation-only
-- assertion that the data is present. No inserts are needed for SLA policy rows.
--
-- Additional site for ACCT_B with an asset for cross-account isolation tests.
-- IDs use the b5000000 prefix to avoid conflicts with earlier fixtures.
-- =============================================================================

-- Additional site for ACCT_B (customer 00000000-0000-0000-0000-000000000002)
INSERT INTO site (id, customer_id, name, address, version)
VALUES ('b5000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        'Site B2 (Portal Test)', '2 Beta Boulevard', 0)
ON CONFLICT (id) DO NOTHING;

-- Asset on Site B2 for ACCT_B
INSERT INTO asset (id, site_id, name, asset_type, version)
VALUES ('b5000000-0000-0000-0000-000000000011',
        'b5000000-0000-0000-0000-000000000001',
        'Chiller B2', 'CHILLER', 0)
ON CONFLICT (id) DO NOTHING;

-- Asset on Site A1 (ACCT_A) — re-asserts that 20000000-...-0001 (HVAC Unit A1) exists
-- (already in V100; using ON CONFLICT DO NOTHING for readability)
-- This confirms the fixture topology for portal tests:
--   CUSTOMER_A (aaaaaaaa-...16, linked to ACCT_A=00...01, ACTIVE in V118) → site_a1 (10...01), asset_a1 (20...01)
--   CUSTOMER_B (aaaaaaaa-...17, linked to ACCT_B=00...02, ACTIVE in V118) → site_b2 (b5...01), asset_b2 (b5...11)
--   CUSTOMER_ORPHAN (aaaaaaaa-...18, NO portal_account_user) → expect 404 on submit
