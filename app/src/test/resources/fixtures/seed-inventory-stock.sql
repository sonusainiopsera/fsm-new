-- =============================================================================
-- Inventory stock fixture for parts availability tests (WO-137)
-- =============================================================================
-- UUID namespace: 00000000-0000-7137-XXXX-XXXXXXXXXXXX
-- IDEMPOTENT: all inserts use ON CONFLICT DO NOTHING.
--
-- Scenarios seeded:
--   Tech 1 (FULLY_STOCKED):    van has all required parts on hand
--   Tech 2 (COLLECTABLE):      van has 0 of part-A; Central Warehouse has sufficient stock
--   Tech 3 (PARTIALLY_STOCKED): van short on part-A; warehouse has some but not enough total
--   Tech 4 (UNAVAILABLE):      van has 0 of part-B; no warehouse stock anywhere
--
-- Work orders:
--   WO-SINGLE:  requires 2 × FLT-001 (part-A)
--   WO-MULTI:   requires 1 × FLT-001 (part-A) AND 1 × BRK-P200 (part-B)
-- =============================================================================

-- ── Part catalogue references (already seeded by V4/V13; referenced here) ────
-- part-A = 'ffffffff-0000-7007-8000-000000000001' (FLT-001  HEPA Air Filter)
-- part-B = 'ffffffff-0000-7007-8000-000000000004' (BRK-P200 Pressure Break Switch)
-- Warehouse = 'ffffffff-0000-7008-8000-000000000001' (Central Warehouse, WAREHOUSE type)

-- ── App users for 4 test technicians ─────────────────────────────────────────

INSERT INTO app_user (id, email, display_name, full_name, active, version)
VALUES
    ('00000000-0000-7137-0010-000000000001'::uuid, 'stock-tech-1@example.test', 'Stock Tech 1', 'Stock Tech 1', true, 0),
    ('00000000-0000-7137-0010-000000000002'::uuid, 'stock-tech-2@example.test', 'Stock Tech 2', 'Stock Tech 2', true, 0),
    ('00000000-0000-7137-0010-000000000003'::uuid, 'stock-tech-3@example.test', 'Stock Tech 3', 'Stock Tech 3', true, 0),
    ('00000000-0000-7137-0010-000000000004'::uuid, 'stock-tech-4@example.test', 'Stock Tech 4', 'Stock Tech 4', true, 0)
ON CONFLICT DO NOTHING;

-- ── Technicians ───────────────────────────────────────────────────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, active, version)
SELECT
    '00000000-0000-7137-0011-000000000001'::uuid,
    '00000000-0000-7137-0010-000000000001'::uuid,
    'STECH-0001', 'Stock Tech 1', 'Stock Tech 1', 'Europe/London', true, 0
WHERE NOT EXISTS (SELECT 1 FROM technician WHERE id = '00000000-0000-7137-0011-000000000001'::uuid);

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, active, version)
SELECT
    '00000000-0000-7137-0011-000000000002'::uuid,
    '00000000-0000-7137-0010-000000000002'::uuid,
    'STECH-0002', 'Stock Tech 2', 'Stock Tech 2', 'Europe/London', true, 0
WHERE NOT EXISTS (SELECT 1 FROM technician WHERE id = '00000000-0000-7137-0011-000000000002'::uuid);

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, active, version)
SELECT
    '00000000-0000-7137-0011-000000000003'::uuid,
    '00000000-0000-7137-0010-000000000003'::uuid,
    'STECH-0003', 'Stock Tech 3', 'Stock Tech 3', 'Europe/London', true, 0
WHERE NOT EXISTS (SELECT 1 FROM technician WHERE id = '00000000-0000-7137-0011-000000000003'::uuid);

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, active, version)
SELECT
    '00000000-0000-7137-0011-000000000004'::uuid,
    '00000000-0000-7137-0010-000000000004'::uuid,
    'STECH-0004', 'Stock Tech 4', 'Stock Tech 4', 'Europe/London', true, 0
WHERE NOT EXISTS (SELECT 1 FROM technician WHERE id = '00000000-0000-7137-0011-000000000004'::uuid);

-- ── Vehicle stock locations (one per technician) ───────────────────────────────

INSERT INTO stock_location (id, name, location_type, technician_id)
VALUES
    ('00000000-0000-7137-0020-000000000001'::uuid, 'Van Stock Tech 1', 'VEHICLE', '00000000-0000-7137-0011-000000000001'::uuid),
    ('00000000-0000-7137-0020-000000000002'::uuid, 'Van Stock Tech 2', 'VEHICLE', '00000000-0000-7137-0011-000000000002'::uuid),
    ('00000000-0000-7137-0020-000000000003'::uuid, 'Van Stock Tech 3', 'VEHICLE', '00000000-0000-7137-0011-000000000003'::uuid),
    ('00000000-0000-7137-0020-000000000004'::uuid, 'Van Stock Tech 4', 'VEHICLE', '00000000-0000-7137-0011-000000000004'::uuid)
ON CONFLICT DO NOTHING;

-- ── Stock balances ────────────────────────────────────────────────────────────
--
-- Scenario 1: Tech 1 van — FULLY_STOCKED
--   part-A (FLT-001): 5 on hand (WO requires 2 → satisfied)
--   part-B (BRK-P200): 3 on hand (WO requires 1 → satisfied)
--
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES
    ('00000000-0000-7137-0030-000000000001'::uuid,
     'ffffffff-0000-7007-8000-000000000001',
     '00000000-0000-7137-0020-000000000001'::uuid, 5, 0),
    ('00000000-0000-7137-0030-000000000002'::uuid,
     'ffffffff-0000-7007-8000-000000000004',
     '00000000-0000-7137-0020-000000000001'::uuid, 3, 0)
ON CONFLICT DO NOTHING;

-- Scenario 2: Tech 2 van — COLLECTABLE
--   part-A (FLT-001): 0 on van; warehouse (Central Warehouse) has 50 → collectable
--   part-B (BRK-P200): 0 on van; warehouse has 20 → collectable
--   (zero-balance rows explicitly seeded to confirm intentional absence)
--
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES
    ('00000000-0000-7137-0030-000000000003'::uuid,
     'ffffffff-0000-7007-8000-000000000001',
     '00000000-0000-7137-0020-000000000002'::uuid, 0, 0),
    ('00000000-0000-7137-0030-000000000004'::uuid,
     'ffffffff-0000-7007-8000-000000000004',
     '00000000-0000-7137-0020-000000000002'::uuid, 0, 0)
ON CONFLICT DO NOTHING;

-- Scenario 3: Tech 3 van — PARTIALLY_STOCKED
--   part-A (FLT-001): 1 on van (requires 2); warehouse has 0 → shortfall of 1, unavailable
--   part-B (BRK-P200): 0 on van (requires 1); warehouse has 1 → collectable
--   → some parts collectable, some not → PARTIALLY_STOCKED
--
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES
    ('00000000-0000-7137-0030-000000000005'::uuid,
     'ffffffff-0000-7007-8000-000000000001',
     '00000000-0000-7137-0020-000000000003'::uuid, 1, 0),
    ('00000000-0000-7137-0030-000000000006'::uuid,
     'ffffffff-0000-7007-8000-000000000004',
     '00000000-0000-7137-0020-000000000003'::uuid, 0, 0)
ON CONFLICT DO NOTHING;

-- Scenario 4: Tech 4 van — UNAVAILABLE
--   part-A (FLT-001): 0 on van; warehouse has 0 → truly unavailable
--   part-B (BRK-P200): 0 on van; warehouse has 0 → truly unavailable
--   (no warehouse balance rows for these parts beyond what's already in Central Warehouse V4)
--
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES
    ('00000000-0000-7137-0030-000000000007'::uuid,
     'ffffffff-0000-7007-8000-000000000001',
     '00000000-0000-7137-0020-000000000004'::uuid, 0, 0),
    ('00000000-0000-7137-0030-000000000008'::uuid,
     'ffffffff-0000-7007-8000-000000000004',
     '00000000-0000-7137-0020-000000000004'::uuid, 0, 0)
ON CONFLICT DO NOTHING;

-- ── Customer and site (needed by work_order FK) ───────────────────────────────

INSERT INTO customer (id, name, version)
SELECT
    '00000000-0000-7137-0040-000000000001'::uuid,
    'Stock Test Customer', 0
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = '00000000-0000-7137-0040-000000000001'::uuid);

INSERT INTO site (id, name, customer_id, address_line1, city, postcode,
                  latitude, longitude, version)
SELECT
    '00000000-0000-7137-0041-000000000001'::uuid,
    'Stock Test Site',
    '00000000-0000-7137-0040-000000000001'::uuid,
    '1 Test Lane', 'London', 'EC1A 1BB', 51.5074, -0.1278, 0
WHERE NOT EXISTS (SELECT 1 FROM site WHERE id = '00000000-0000-7137-0041-000000000001'::uuid);

-- ── Work orders for parts availability tests ──────────────────────────────────

-- WO-SINGLE: single-part requirement (2 × FLT-001 HEPA Air Filter)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        fault_category, description, version)
SELECT
    '00000000-0000-7137-0050-000000000001'::uuid,
    'STOCK-WO-001', 'NEW', 'MEDIUM',
    '00000000-0000-7137-0041-000000000001'::uuid,
    'HVAC_MAINTENANCE', 'Filter replacement — single part', 0
WHERE NOT EXISTS (SELECT 1 FROM work_order WHERE id = '00000000-0000-7137-0050-000000000001'::uuid);

-- WO-MULTI: multi-part requirement (1 × FLT-001 AND 1 × BRK-P200)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        fault_category, description, version)
SELECT
    '00000000-0000-7137-0050-000000000002'::uuid,
    'STOCK-WO-002', 'NEW', 'HIGH',
    '00000000-0000-7137-0041-000000000001'::uuid,
    'HVAC_MAINTENANCE', 'Compressor repair — multi-part', 0
WHERE NOT EXISTS (SELECT 1 FROM work_order WHERE id = '00000000-0000-7137-0050-000000000002'::uuid);

-- ── Work order required parts ─────────────────────────────────────────────────

-- WO-SINGLE requires 2 × FLT-001
INSERT INTO work_order_required_part (id, work_order_id, part_id, quantity_required)
VALUES
    ('00000000-0000-7137-0060-000000000001'::uuid,
     '00000000-0000-7137-0050-000000000001'::uuid,
     'ffffffff-0000-7007-8000-000000000001', 2)
ON CONFLICT DO NOTHING;

-- WO-MULTI requires 1 × FLT-001 AND 1 × BRK-P200
INSERT INTO work_order_required_part (id, work_order_id, part_id, quantity_required)
VALUES
    ('00000000-0000-7137-0060-000000000002'::uuid,
     '00000000-0000-7137-0050-000000000002'::uuid,
     'ffffffff-0000-7007-8000-000000000001', 1),
    ('00000000-0000-7137-0060-000000000003'::uuid,
     '00000000-0000-7137-0050-000000000002'::uuid,
     'ffffffff-0000-7007-8000-000000000004', 1)
ON CONFLICT DO NOTHING;
