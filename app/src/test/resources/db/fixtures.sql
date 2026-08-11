-- Test fixtures for cross-role probe matrix tests.
-- Two customers, three sites, four work orders, two technicians.
--
-- UUID constants:
--   App users:    user-t1 (dddddddd-...-0001), user-t2 (dddddddd-...-0002)
--   Customers:    acct-0001 (aaaaaaaa-...-0001 Acme), acct-0002 (aaaaaaaa-...-0002 Beta)
--   Sites:        site-0001 (Acme HQ), site-0002 (Beta HQ), site-0003 (Acme Branch)
--   Technicians:  tech-0001 (cccccccc-...-0001), tech-0002 (cccccccc-...-0002)
--   Work orders:  wo-0001 (Acme HQ, tech-0001), wo-0002 (Beta HQ, tech-0002)
--                 wo-0003 (Acme Branch, tech-0001), wo-0004 (Beta HQ, unassigned)

-- ---- App users (required FK parent for technicians) -------------------------
INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('dddddddd-0000-0000-0000-000000000001', 'tech1@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech One', TRUE, 0),
    ('dddddddd-0000-0000-0000-000000000002', 'tech2@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech Two', TRUE, 0);

-- ---- Customers (was customer_account) ----------------------------------------
INSERT INTO customer (id, name, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Acme Corp', 0),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Beta Ltd',  0);

-- ---- Sites ------------------------------------------------------------------
INSERT INTO site (id, name, customer_id, version) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001', 'Acme HQ',     'aaaaaaaa-0000-0000-0000-000000000001', 0),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'Beta HQ',     'aaaaaaaa-0000-0000-0000-000000000002', 0),
    ('bbbbbbbb-0000-0000-0000-000000000003', 'Acme Branch', 'aaaaaaaa-0000-0000-0000-000000000001', 0);

-- ---- Technicians (required FK parent for work_order.assigned_technician_id) --
INSERT INTO technician (id, user_id, full_name, version) VALUES
    ('cccccccc-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000001', 'Tech One', 0),
    ('cccccccc-0000-0000-0000-000000000002', 'dddddddd-0000-0000-0000-000000000002', 'Tech Two', 0);

-- ---- Work orders ------------------------------------------------------------
-- WO-001: Acme HQ, assigned to Tech One
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000001', 'WO-001', 'ASSIGNED', 'MEDIUM',
     'bbbbbbbb-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', 0);

-- WO-002: Beta HQ, assigned to Tech Two
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000002', 'WO-002', 'ASSIGNED', 'MEDIUM',
     'bbbbbbbb-0000-0000-0000-000000000002', 'cccccccc-0000-0000-0000-000000000002', 0);

-- WO-003: Acme Branch, assigned to Tech One (same tech, different Acme site)
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000003', 'WO-003', 'NEW', 'LOW',
     'bbbbbbbb-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000001', 0);

-- WO-004: Beta HQ, unassigned
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000004', 'WO-004', 'NEW', 'LOW',
     'bbbbbbbb-0000-0000-0000-000000000002', NULL, 0);
