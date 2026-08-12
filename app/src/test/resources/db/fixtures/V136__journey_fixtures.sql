-- =============================================================================
-- V136: Technician journey regression fixtures (WO-160)
--
-- Provides a stable baseline for TechnicianJourneyIT and the Playwright
-- technician-mobile E2E suite. All data is synthetic and anonymised.
--
-- Fixture topology:
--   Two technicians: TECH_1 (EMP-001) and TECH_2 (EMP-002) — reuse V100 IDs
--   Two job states: one ASSIGNED to TECH_1, one IN_PROGRESS TECH_1
--   Stock: 10 units PN-002 at TECH_1 Van A (reuse V100 part/location)
--          0 units PN-004 at TECH_1 Van A (for over-consumption tests)
--   One job assigned to TECH_2 (for cross-technician 403 test)
--
-- ID ranges: e1000000-* (journey prefix, no collision with existing fixtures)
-- =============================================================================

-- Journey test customer (separate from V100 ACCT_A to avoid interference)
INSERT INTO customer (id, name, contact_phone, is_active, version)
VALUES ('e1000000-0000-0000-0000-000000000001', 'Journey Test Corp', '+15555550300', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, customer_id, name, address, latitude, longitude, version)
VALUES ('e1000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000001',
        'Journey Test Site Alpha', '1 Journey Lane, Test City', 34.010, -118.010, 0)
ON CONFLICT (id) DO NOTHING;

-- Journey work order 1: ASSIGNED to TECH_1 (golden-path start)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, version)
VALUES ('e2000000-0000-0000-0000-000000000001',
        'e1000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'ASSIGNED', 'HIGH', 'HVAC Inspection — Journey Test WO', 0)
ON CONFLICT DO NOTHING;

-- Journey work order 2: IN_PROGRESS TECH_1 — no labour time (tests completion guard)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, version)
VALUES ('e2000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'IN_PROGRESS', 'MEDIUM', 'Electrical Fault — No Labour Time', 0)
ON CONFLICT DO NOTHING;

-- Journey work order 3: ASSIGNED to TECH_2 (cross-tech 403 test)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, version)
VALUES ('e2000000-0000-0000-0000-000000000003',
        'e1000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000012',
        'ASSIGNED', 'LOW', 'Cross-Tech Access Test WO', 0)
ON CONFLICT DO NOTHING;

-- Stock: 10 units PN-002 at TECH_1 Van A — ensure non-zero balance for consumption tests.
-- V100 seeds the stock_location but may not set Van A's balance; V109 sets PN-003/PN-004.
-- This row provides PN-002 at Van A for journey tests.
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES ('e3000000-0000-0000-0000-000000000001',
        '50000000-0000-0000-0000-000000000002',  -- PN-002 Air Filter
        '60000000-0000-0000-0000-000000000011',  -- TECH_1 Van A
        10, 0)
ON CONFLICT (part_id, location_id) DO UPDATE
    SET quantity_on_hand = GREATEST(stock_balance.quantity_on_hand, 10);
