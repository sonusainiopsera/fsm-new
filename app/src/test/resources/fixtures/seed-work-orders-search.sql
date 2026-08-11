-- Deterministic fixture for WorkOrderSearchIT.
-- IDs use fixed UUIDs so tests can assert specific rows.
--
-- Schema:
--   customer:  ff000000-...-0001 (SearchCorp), ff000000-...-0002 (OtherCorp)
--   site:      ff000000-...-0011 (Main Site, SearchCorp), ff000000-...-0012 (Branch, SearchCorp)
--              ff000000-...-0013 (Other Site, OtherCorp)
--   app_user:  ff000000-...-0021 (Tech A), ff000000-...-0022 (Tech B)
--   technician: ff000000-...-0031 (Tech A), ff000000-...-0032 (Tech B)
--   work_order: ff000000-...-0041..0046

INSERT INTO customer (id, name, version) VALUES
    ('ff000000-0000-0000-0000-000000000001', 'SearchCorp',  0),
    ('ff000000-0000-0000-0000-000000000002', 'OtherCorp',   0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, name, customer_id, version) VALUES
    ('ff000000-0000-0000-0000-000000000011', 'Main Site',   'ff000000-0000-0000-0000-000000000001', 0),
    ('ff000000-0000-0000-0000-000000000012', 'Branch Site', 'ff000000-0000-0000-0000-000000000001', 0),
    ('ff000000-0000-0000-0000-000000000013', 'Other Site',  'ff000000-0000-0000-0000-000000000002', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('ff000000-0000-0000-0000-000000000021', 'tech-a@search.test',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Tech Alpha', TRUE, 0),
    ('ff000000-0000-0000-0000-000000000022', 'tech-b@search.test',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Tech Beta',  TRUE, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, full_name, version) VALUES
    ('ff000000-0000-0000-0000-000000000031', 'ff000000-0000-0000-0000-000000000021', 'Tech Alpha', 0),
    ('ff000000-0000-0000-0000-000000000032', 'ff000000-0000-0000-0000-000000000022', 'Tech Beta',  0)
ON CONFLICT (id) DO NOTHING;

-- Six work orders: mixed states, priorities, sites, technicians
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     at_risk, cumulative_hold_minutes, version)
VALUES
    ('ff000000-0000-0000-0000-000000000041', 'SRCH-001', 'NEW',         'HIGH',     'ff000000-0000-0000-0000-000000000011', NULL, FALSE, 0, 0),
    ('ff000000-0000-0000-0000-000000000042', 'SRCH-002', 'ASSIGNED',    'MEDIUM',   'ff000000-0000-0000-0000-000000000011', 'ff000000-0000-0000-0000-000000000031', FALSE, 0, 0),
    ('ff000000-0000-0000-0000-000000000043', 'SRCH-003', 'IN_PROGRESS', 'HIGH',     'ff000000-0000-0000-0000-000000000011', 'ff000000-0000-0000-0000-000000000031', TRUE,  0, 0),
    ('ff000000-0000-0000-0000-000000000044', 'SRCH-004', 'ON_HOLD',     'LOW',      'ff000000-0000-0000-0000-000000000012', 'ff000000-0000-0000-0000-000000000032', FALSE, 30, 0),
    ('ff000000-0000-0000-0000-000000000045', 'SRCH-005', 'COMPLETED',   'CRITICAL', 'ff000000-0000-0000-0000-000000000012', 'ff000000-0000-0000-0000-000000000032', FALSE, 0, 0),
    ('ff000000-0000-0000-0000-000000000046', 'SRCH-006', 'IN_PROGRESS', 'MEDIUM',   'ff000000-0000-0000-0000-000000000013', 'ff000000-0000-0000-0000-000000000031', FALSE, 0, 0)
ON CONFLICT (id) DO NOTHING;
