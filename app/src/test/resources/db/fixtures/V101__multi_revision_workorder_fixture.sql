-- =============================================================================
-- V101: Multi-revision work order fixture for audit and revision query tests
-- =============================================================================
-- Directly inserts REVINFO and work_order_aud rows to produce a pre-built
-- three-revision history (ADD → MOD → DEL) for work order WO_MULTI.
--
-- These rows represent the full lifecycle of a work order under a DISPATCHER
-- actor, used by revision query endpoint tests and audit assertion tests that
-- need stable, pre-seeded data rather than programmatically created revisions.
--
-- Rev numbers 9001–9003 are used to avoid conflicts with sequence-generated
-- revisions created during tests. The sequence is advanced to 9003 at the end.
-- =============================================================================

-- REVINFO rows for the three lifecycle revisions
INSERT INTO revinfo (rev, rev_tstmp, actor_user_id, actor_role, trace_id, client_ip) VALUES
    (9001, 1700000000000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-fixture-9001', '127.0.0.1'),
    (9002, 1700000060000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-fixture-9002', '127.0.0.1'),
    (9003, 1700000120000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-fixture-9003', '127.0.0.1');

-- work_order_aud: ADD (revtype=0), MOD (revtype=1), DEL (revtype=2)
-- site_id and customer_id reference existing fixture data (no FK constraint to main tables)
INSERT INTO work_order_aud (id, rev, revtype, site_id, customer_id, state, priority, title, description, sla_deadline, assigned_technician_id, created_at, updated_at) VALUES
    ('40000000-0000-0000-0000-000000000001', 9001, 0,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'NEW', 'HIGH', 'Fixture WO - Created',
     'Initial description from fixture ADD revision.',
     '2023-11-15 12:00:00+00', NULL,
     '2023-11-15 10:00:00+00', '2023-11-15 10:00:00+00'),

    ('40000000-0000-0000-0000-000000000001', 9002, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'IN_PROGRESS', 'HIGH', 'Fixture WO - Updated',
     'Updated description from fixture MOD revision.',
     '2023-11-15 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 10:00:00+00', '2023-11-15 10:01:00+00'),

    ('40000000-0000-0000-0000-000000000001', 9003, 2,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'CLOSED', 'HIGH', 'Fixture WO - Updated',
     'Updated description from fixture MOD revision.',
     '2023-11-15 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 10:00:00+00', '2023-11-15 10:02:00+00');

-- Advance the sequence past the fixture's max rev to avoid future conflicts
SELECT setval('revinfo_seq', 9003);
