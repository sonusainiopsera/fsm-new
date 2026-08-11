-- Test fixtures for cross-role probe matrix tests.
-- Two customer accounts, three sites, four work orders,
-- two technicians, plus one multi-account customer user (modelled via JWT claims).
--
-- UUID constants:
--   Customer accounts:  acct-0001, acct-0002
--   Sites:              site-0001 (Acme HQ, acct-0001)
--                       site-0002 (Beta HQ, acct-0002)
--                       site-0003 (Acme Branch, acct-0001)
--   Work orders:        wo-0001 (Acme HQ, tech-0001)
--                       wo-0002 (Beta HQ, tech-0002)
--                       wo-0003 (Acme Branch, tech-0001)   -- same tech, different account site
--                       wo-0004 (Beta HQ, unassigned)
--   Technician IDs:     tech-0001 (user user-t1)
--                       tech-0002 (user user-t2)
--   Customer user:      user-c1  linked to BOTH acct-0001 AND acct-0002 (via JWT claim)
--                       user-c2  linked to ONLY acct-0002

-- ---- Customer accounts -------------------------------------------------------
INSERT INTO customer_account (id, name, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Acme Corp', 0),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Beta Ltd',  0);

-- ---- Sites -------------------------------------------------------------------
INSERT INTO site (id, name, customer_account_id, version) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001', 'Acme HQ',     'aaaaaaaa-0000-0000-0000-000000000001', 0),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'Beta HQ',     'aaaaaaaa-0000-0000-0000-000000000002', 0),
    ('bbbbbbbb-0000-0000-0000-000000000003', 'Acme Branch', 'aaaaaaaa-0000-0000-0000-000000000001', 0);

-- ---- Work orders -------------------------------------------------------------
-- WO-001: Acme site, assigned to Tech One
INSERT INTO work_order (id, reference, status, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000001', 'WO-001', 'ASSIGNED',
     'bbbbbbbb-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', 0);

-- WO-002: Beta site, assigned to Tech Two
INSERT INTO work_order (id, reference, status, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000002', 'WO-002', 'ASSIGNED',
     'bbbbbbbb-0000-0000-0000-000000000002', 'cccccccc-0000-0000-0000-000000000002', 0);

-- WO-003: Acme Branch (also Acme account), assigned to Tech One
-- Tech One has 2 work orders across different Acme sites.
INSERT INTO work_order (id, reference, status, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000003', 'WO-003', 'OPEN',
     'bbbbbbbb-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000001', 0);

-- WO-004: Beta site, unassigned
INSERT INTO work_order (id, reference, status, site_id, assigned_technician_id, version) VALUES
    ('eeeeeeee-0000-0000-0000-000000000004', 'WO-004', 'OPEN',
     'bbbbbbbb-0000-0000-0000-000000000002', NULL, 0);
