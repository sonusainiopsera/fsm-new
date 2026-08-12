-- thin-grounding-seed.sql
-- Fixture for CopilotStreamSafetyTest: asset with no prior service history.
-- Seeds a work order assigned to TECH_1 with a linked asset but zero prior closed WOs,
-- which triggers the INSUFFICIENT verdict → no_grounded_basis terminal event.
--
-- IDs are in the dd000000-* range (distinct from aa/cc/ff ranges used elsewhere).

-- ─── Customer ─────────────────────────────────────────────────────────────────
INSERT INTO customer (id, name, legal_name, primary_contact_name, primary_contact_email,
                      primary_contact_phone, contact_email, contact_phone,
                      billing_address, is_active, version)
VALUES (
    'dd000000-0000-0000-0000-000000000001',
    'Thin Grounding Corp',
    'Thin Grounding Corp Ltd',
    'Test Contact',
    'test@example.com',
    '+15555550400',
    'test@example.com',
    '+15555550400',
    '1 Test Street, Fakeville',
    true, 0
) ON CONFLICT (id) DO NOTHING;

-- ─── Site ──────────────────────────────────────────────────────────────────────
INSERT INTO site (id, customer_id, name, address, postcode, latitude, longitude, is_active, version)
VALUES (
    'dd000000-0000-0000-0000-000000000010',
    'dd000000-0000-0000-0000-000000000001',
    'Thin Grounding Site',
    '1 Test Street, Fakeville',
    'FK1 0TG',
    51.0, -0.1,
    true, 0
) ON CONFLICT (id) DO NOTHING;

-- ─── Asset (new unit, zero prior service history) ──────────────────────────────
INSERT INTO asset (id, site_id, name, asset_type, serial_no, model, category, manufacturer, version)
VALUES (
    'dd000000-0000-0000-0000-000000000020',
    'dd000000-0000-0000-0000-000000000010',
    'New HVAC Unit DD-001',
    'HVAC',
    'SN-HVAC-DD-001',
    'CoolMax 100',
    'HVAC',
    'CoolTech',
    0
) ON CONFLICT (id) DO NOTHING;

-- ─── Work order assigned to TECH_1, linked to the new asset, no fault description ──
INSERT INTO work_order (id, customer_id, site_id, asset_id, assigned_technician_id,
                        state, priority, title, fault_description, origin, version)
VALUES (
    'dd000000-0000-0000-0000-000000000030',
    'dd000000-0000-0000-0000-000000000001',
    'dd000000-0000-0000-0000-000000000010',
    'dd000000-0000-0000-0000-000000000020',
    '00000000-0000-0000-0000-000000000011',  -- TECH_1_ID
    'IN_PROGRESS', 'MEDIUM',
    'New unit inspection',
    NULL,   -- No fault description → combined with no prior history → INSUFFICIENT
    'DISPATCHER', 0
) ON CONFLICT (id) DO NOTHING;
