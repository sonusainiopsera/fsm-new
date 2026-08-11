-- =============================================================================
-- V122: Work order history lifecycle fixture for WO-129 timeline and revision tests
-- =============================================================================
-- Inserts REVINFO and work_order_aud rows representing a full lifecycle:
--   NEW → ASSIGNED → EN_ROUTE → IN_PROGRESS → ON_HOLD → IN_PROGRESS → COMPLETED → CLOSED
-- for work order WO_LIFECYCLE (id: 60000000-0000-0000-0000-000000000001).
--
-- IDs use the 60000000 prefix to avoid conflicts with V100-V102 fixtures.
-- Rev numbers 9100–9114 to avoid conflicts with V101 (9001–9003).
-- =============================================================================

-- -----------------------------------------------------------------------
-- REVINFO rows
-- -----------------------------------------------------------------------
INSERT INTO revinfo (rev, rev_tstmp, actor_user_id, actor_role, trace_id, client_ip) VALUES
    -- WO_LIFECYCLE full lifecycle (9100–9107)
    (9100, 1700100000000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9100', '10.0.0.1'),
    (9101, 1700100060000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9101', '10.0.0.1'),
    (9102, 1700100120000, 'aaaaaaaa-0000-0000-0000-000000000011', 'TECHNICIAN', 'trace-history-9102', '10.0.0.2'),
    (9103, 1700100180000, 'aaaaaaaa-0000-0000-0000-000000000011', 'TECHNICIAN', 'trace-history-9103', '10.0.0.2'),
    (9104, 1700100240000, 'aaaaaaaa-0000-0000-0000-000000000011', 'TECHNICIAN', 'trace-history-9104', '10.0.0.2'),
    (9105, 1700100300000, 'aaaaaaaa-0000-0000-0000-000000000011', 'TECHNICIAN', 'trace-history-9105', '10.0.0.2'),
    (9106, 1700100360000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9106', '10.0.0.1'),
    (9107, 1700100420000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9107', '10.0.0.1'),
    -- WO_REASSIGNED (tech1 → tech2, same state ASSIGNED) (9108–9110)
    (9108, 1700200000000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9108', '10.0.0.1'),
    (9109, 1700200060000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9109', '10.0.0.1'),
    (9110, 1700200120000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9110', '10.0.0.1'),
    -- WO_CANCELLED (9111–9112)
    (9111, 1700300000000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9111', '10.0.0.1'),
    (9112, 1700300060000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9112', '10.0.0.1'),
    -- WO_CUSTOMER_VIEW (customer account ACCT_A) (9113–9114)
    (9113, 1700400000000, 'aaaaaaaa-0000-0000-0000-000000000001', 'DISPATCHER', 'trace-history-9113', '10.0.0.1'),
    (9114, 1700400060000, 'aaaaaaaa-0000-0000-0000-000000000011', 'TECHNICIAN', 'trace-history-9114', '10.0.0.2');

-- -----------------------------------------------------------------------
-- WO_LIFECYCLE: full lifecycle from NEW → CLOSED (id 60000000-...-0001)
-- site_id: 10000000-...-0001 (Site A1, customer ACCT_A = 00000000-...-0001)
-- -----------------------------------------------------------------------
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, reference,
                        assigned_technician_id, description, fault_description,
                        sla_deadline, version)
VALUES ('60000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'CLOSED', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
        '00000000-0000-0000-0000-000000000011',
        'Lifecycle test work order description.',
        'Fault: sensor failure on unit A3.',
        '2023-11-16 12:00:00+00', 0);

INSERT INTO work_order_aud (id, rev, revtype, site_id, customer_id, state, priority, title,
                             reference, description, fault_description, sla_deadline,
                             assigned_technician_id, created_at, updated_at)
VALUES
    -- rev 9100: ADD (NEW)
    ('60000000-0000-0000-0000-000000000001', 9100, 0,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'NEW', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', NULL,
     '2023-11-16 12:00:00+00', NULL,
     '2023-11-15 00:00:00+00', '2023-11-15 00:00:00+00'),
    -- rev 9101: MOD (ASSIGNED, tech1)
    ('60000000-0000-0000-0000-000000000001', 9101, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'ASSIGNED', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', NULL,
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:01:00+00'),
    -- rev 9102: MOD (EN_ROUTE)
    ('60000000-0000-0000-0000-000000000001', 9102, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'EN_ROUTE', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', NULL,
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:02:00+00'),
    -- rev 9103: MOD (IN_PROGRESS)
    ('60000000-0000-0000-0000-000000000001', 9103, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'IN_PROGRESS', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', 'Fault: sensor failure on unit A3.',
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:03:00+00'),
    -- rev 9104: MOD (ON_HOLD)
    ('60000000-0000-0000-0000-000000000001', 9104, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'ON_HOLD', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', 'Fault: sensor failure on unit A3.',
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:04:00+00'),
    -- rev 9105: MOD (IN_PROGRESS — resumed)
    ('60000000-0000-0000-0000-000000000001', 9105, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'IN_PROGRESS', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', 'Fault: sensor failure on unit A3.',
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:05:00+00'),
    -- rev 9106: MOD (COMPLETED)
    ('60000000-0000-0000-0000-000000000001', 9106, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'COMPLETED', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', 'Fault: sensor failure on unit A3.',
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:06:00+00'),
    -- rev 9107: MOD (CLOSED)
    ('60000000-0000-0000-0000-000000000001', 9107, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'CLOSED', 'HIGH', 'Lifecycle fixture WO', 'REF-HIST-001',
     'Lifecycle test work order description.', 'Fault: sensor failure on unit A3.',
     '2023-11-16 12:00:00+00', '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:07:00+00');

-- -----------------------------------------------------------------------
-- WO_REASSIGNED: ADD → ASSIGNED(tech1) → REASSIGNED(tech2) (id 60000000-...-0002)
-- -----------------------------------------------------------------------
INSERT INTO work_order (id, site_id, customer_id, state, priority, title,
                        assigned_technician_id, version)
VALUES ('60000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'ASSIGNED', 'MEDIUM', 'Reassignment test WO',
        '00000000-0000-0000-0000-000000000012', 0);

INSERT INTO work_order_aud (id, rev, revtype, site_id, customer_id, state, priority, title,
                             assigned_technician_id, created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000002', 9108, 0,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'NEW', 'MEDIUM', 'Reassignment test WO', NULL,
     '2023-11-15 00:00:00+00', '2023-11-15 00:00:00+00'),
    ('60000000-0000-0000-0000-000000000002', 9109, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'ASSIGNED', 'MEDIUM', 'Reassignment test WO',
     '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:01:00+00'),
    ('60000000-0000-0000-0000-000000000002', 9110, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'ASSIGNED', 'MEDIUM', 'Reassignment test WO',
     '00000000-0000-0000-0000-000000000012',
     '2023-11-15 00:00:00+00', '2023-11-15 00:02:00+00');

-- -----------------------------------------------------------------------
-- WO_CANCELLED: ADD → CANCELLED (id 60000000-...-0003)
-- -----------------------------------------------------------------------
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, version)
VALUES ('60000000-0000-0000-0000-000000000003',
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'CANCELLED', 'LOW', 'Cancelled fixture WO', 0);

INSERT INTO work_order_aud (id, rev, revtype, site_id, customer_id, state, priority, title,
                             created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000003', 9111, 0,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'NEW', 'LOW', 'Cancelled fixture WO',
     '2023-11-15 00:00:00+00', '2023-11-15 00:00:00+00'),
    ('60000000-0000-0000-0000-000000000003', 9112, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'CANCELLED', 'LOW', 'Cancelled fixture WO',
     '2023-11-15 00:00:00+00', '2023-11-15 00:01:00+00');

-- -----------------------------------------------------------------------
-- WO_CUSTOMER_VIEW: belongs to ACCT_A (customer 00000000-...-0001)
-- Used for customer-role scoping and masking assertions (id 60000000-...-0004)
-- -----------------------------------------------------------------------
INSERT INTO work_order (id, site_id, customer_id, state, priority, title,
                        description, fault_description,
                        assigned_technician_id, version)
VALUES ('60000000-0000-0000-0000-000000000004',
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'ASSIGNED', 'MEDIUM', 'Customer-view fixture WO',
        'This is an internal description.',
        'Confidential fault details.',
        '00000000-0000-0000-0000-000000000011', 0);

INSERT INTO work_order_aud (id, rev, revtype, site_id, customer_id, state, priority, title,
                             description, fault_description, assigned_technician_id,
                             created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000004', 9113, 0,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'NEW', 'MEDIUM', 'Customer-view fixture WO',
     'This is an internal description.', NULL, NULL,
     '2023-11-15 00:00:00+00', '2023-11-15 00:00:00+00'),
    ('60000000-0000-0000-0000-000000000004', 9114, 1,
     '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'ASSIGNED', 'MEDIUM', 'Customer-view fixture WO',
     'This is an internal description.', 'Confidential fault details.',
     '00000000-0000-0000-0000-000000000011',
     '2023-11-15 00:00:00+00', '2023-11-15 00:01:00+00');

-- Advance sequence past fixture revisions
SELECT setval('revinfo_seq', 9114);
