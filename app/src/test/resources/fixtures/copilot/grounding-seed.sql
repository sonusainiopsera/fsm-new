-- grounding-seed.sql
-- Fixtures for GroundingContextIntegrationTest (WO-177).
-- Uses entirely synthetic, fabricated data — no real personal information.
--
-- UUID prefix 7177 = WO-177 copilot grounding fixtures
--
-- Customer   00000000-0000-7177-8000-000000000001  Gamma Meridian Ltd (with PII-shaped values)
-- Site       00000000-0000-7177-8000-000000000011  Gamma Meridian HQ
-- Asset-1    00000000-0000-7177-8000-000000000021  HVAC unit with 3 prior closed WOs
-- Asset-2    00000000-0000-7177-8000-000000000022  Pump with no prior WOs
-- Asset-3    00000000-0000-7177-8000-000000000023  Boiler with 1 prior WO
-- WOs        00000000-0000-7177-8000-000000000030+

-- ---- Customer with PII-shaped values (entirely fabricated) ------------------
INSERT INTO customer (id, name, account_code, legal_name, active,
                      contact_email, phone,
                      primary_contact_name, primary_contact_email, primary_contact_phone,
                      billing_address, version)
VALUES (
    '00000000-0000-7177-8000-000000000001',
    'Gamma Meridian Ltd',
    'GMD-001',
    'Gamma Meridian Limited',
    TRUE,
    'j.thornton@gammameridian.example',
    '+44 7700 900123',
    'James Thornton',
    'james.thornton@gammameridian.example',
    '07700 900456',
    '42 Oakfield Road, Manchester, M14 5AP',
    0
) ON CONFLICT (id) DO NOTHING;

-- ---- Site at a realistic but fabricated UK address --------------------------
INSERT INTO site (id, name, site_code, display_name, customer_id,
                  address_line1, city, postcode, active, version)
VALUES (
    '00000000-0000-7177-8000-000000000011',
    'Gamma Meridian HQ',
    'GMD-S01',
    'Gamma Meridian HQ',
    '00000000-0000-7177-8000-000000000001',
    '42 Oakfield Road',
    'Manchester',
    'M14 5AP',
    TRUE,
    0
) ON CONFLICT (id) DO NOTHING;

-- ---- Assets -----------------------------------------------------------------
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    -- Asset 1: HVAC unit — has 3 prior closed work orders
    ('00000000-0000-7177-8000-000000000021',
     '00000000-0000-7177-8000-000000000011',
     'GMD-HVAC-001', 'SN-HVAC-20230401', 'TurboAir 5000', 'AirTech Industries', 'HVAC', TRUE, 0),
    -- Asset 2: pump — no prior work orders
    ('00000000-0000-7177-8000-000000000022',
     '00000000-0000-7177-8000-000000000011',
     'GMD-PUMP-001', 'SN-PUMP-20230502', 'HydroFlow 200', 'FluidSystems Co', 'PUMP', TRUE, 0),
    -- Asset 3: boiler — 1 prior work order
    ('00000000-0000-7177-8000-000000000023',
     '00000000-0000-7177-8000-000000000011',
     'GMD-BOIL-001', 'SN-BOIL-20230601', 'HeatMaster 3000', 'ThermalTech Ltd', 'BOILER', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Work orders ------------------------------------------------------------
-- Current WO for tech-one on asset-1 (IN_PROGRESS, with PII in description)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7177-8000-000000000031',
    'WO177-031', 'IN_PROGRESS', 'HIGH',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'James Thornton at Gamma Meridian Ltd reports unusual noise from HVAC unit at 42 Oakfield Road M14 5AP. Contact: james.thornton@gammameridian.example or 07700 900456.',
    'HVAC-NOISE', 'MECHANICAL',
    FALSE, 0, 0, 'DISPATCHER'
) ON CONFLICT (id) DO NOTHING;

-- Prior WO-1 on asset-1 (CLOSED, contains email in description)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin, created_at)
VALUES (
    '00000000-0000-7177-8000-000000000032',
    'WO177-032', 'CLOSED', 'MEDIUM',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'Replaced air filter unit. Reported by j.thornton@gammameridian.example at Gamma Meridian HQ.',
    'HVAC-FILTER', 'PREVENTIVE',
    FALSE, 0, 1, 'DISPATCHER', '2025-03-01T10:00:00Z'
) ON CONFLICT (id) DO NOTHING;

-- Prior WO-2 on asset-1 (COMPLETED, contains phone in description)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin, created_at)
VALUES (
    '00000000-0000-7177-8000-000000000033',
    'WO177-033', 'COMPLETED', 'HIGH',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'Compressor bearing replaced. Customer called +44 7700 900123 to confirm fix for Gamma Meridian Ltd.',
    'HVAC-COMP', 'MECHANICAL',
    FALSE, 0, 1, 'DISPATCHER', '2025-01-15T09:00:00Z'
) ON CONFLICT (id) DO NOTHING;

-- Prior WO-3 on asset-1 (CLOSED, older)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin, created_at)
VALUES (
    '00000000-0000-7177-8000-000000000034',
    'WO177-034', 'CLOSED', 'LOW',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'Annual service completed. Site: 42 Oakfield Road M14 5AP.',
    'HVAC-SERVICE', 'PREVENTIVE',
    FALSE, 0, 1, 'DISPATCHER', '2024-11-01T08:00:00Z'
) ON CONFLICT (id) DO NOTHING;

-- Current WO on asset-2 (no prior WOs — for INSUFFICIENT verdict test)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7177-8000-000000000035',
    'WO177-035', 'IN_PROGRESS', 'MEDIUM',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000022',
    'cccccccc-0000-0000-0000-000000000001',
    'Pump vibration detected.',
    NULL, NULL,
    FALSE, 0, 0, 'DISPATCHER'
) ON CONFLICT (id) DO NOTHING;

-- Current WO on asset-3 (1 prior WO — sufficient via prior history)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7177-8000-000000000036',
    'WO177-036', 'IN_PROGRESS', 'HIGH',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000023',
    'cccccccc-0000-0000-0000-000000000001',
    'Boiler not reaching target temperature.',
    'BOIL-TEMP', NULL,
    FALSE, 0, 0, 'DISPATCHER'
) ON CONFLICT (id) DO NOTHING;

-- Prior WO on asset-3 (COMPLETED)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin, created_at)
VALUES (
    '00000000-0000-7177-8000-000000000037',
    'WO177-037', 'COMPLETED', 'MEDIUM',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000023',
    'cccccccc-0000-0000-0000-000000000001',
    'Thermostat replaced and system re-commissioned.',
    'BOIL-THERM', 'ELECTRICAL',
    FALSE, 0, 1, 'DISPATCHER', '2025-02-10T11:00:00Z'
) ON CONFLICT (id) DO NOTHING;

-- WO for tech-two on asset-1 — used for access-control test (tech-one must not see it)
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7177-8000-000000000038',
    'WO177-038', 'IN_PROGRESS', 'LOW',
    '00000000-0000-7177-8000-000000000011',
    '00000000-0000-7177-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000002',
    'Fan belt inspection.',
    'HVAC-BELT', 'MECHANICAL',
    FALSE, 0, 0, 'DISPATCHER'
) ON CONFLICT (id) DO NOTHING;
