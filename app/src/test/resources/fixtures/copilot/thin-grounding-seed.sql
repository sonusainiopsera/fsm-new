-- thin-grounding-seed.sql
-- Fixtures for CopilotStreamSafetyTest (WO-178).
-- Seeds a work order with sufficient fault context (passes R1 and R2) but
-- no fault classification AND no prior work orders for the asset, triggering
-- the R3_THIN_HISTORY_NO_FAULT_CLASSIFICATION sufficiency rule so the copilot
-- endpoint emits a no_grounded_basis terminal event with zero provider calls.
--
-- UUID prefix 7178 = WO-178 copilot stream fixtures
--
-- Customer  00000000-0000-7178-8000-000000000001
-- Site      00000000-0000-7178-8000-000000000011
-- Asset     00000000-0000-7178-8000-000000000021  (thin: no prior WOs)
-- WO-THIN   00000000-0000-7178-8000-000000000031  (R3 INSUFFICIENT: no faultCode/faultCategory)
-- WO-FULL   00000000-0000-7178-8000-000000000032  (SUFFICIENT: has faultCode+faultCategory, no priors)
-- WO-NOASSET 00000000-0000-7178-8000-000000000033 (R1 INSUFFICIENT: null asset_id)

-- ── Customer ─────────────────────────────────────────────────────────────────
INSERT INTO customer (id, name, account_code, legal_name, active,
                      contact_email, phone,
                      primary_contact_name, primary_contact_email, primary_contact_phone,
                      billing_address, version)
VALUES (
    '00000000-0000-7178-8000-000000000001',
    'Delta Holdings Ltd',
    'DHL-001',
    'Delta Holdings Limited',
    TRUE,
    'ops@deltaholdings.example',
    '+44 20 1234 5678',
    'Operations Team',
    'ops@deltaholdings.example',
    '+44 20 1234 5679',
    '10 Fleet Street, London, EC4Y 1AA',
    0
) ON CONFLICT (id) DO NOTHING;

-- ── Site ─────────────────────────────────────────────────────────────────────
INSERT INTO site (id, name, site_code, display_name, customer_id,
                  address_line1, city, postcode, active, version)
VALUES (
    '00000000-0000-7178-8000-000000000011',
    'Delta HQ',
    'DHL-S01',
    'Delta Holdings HQ',
    '00000000-0000-7178-8000-000000000001',
    '10 Fleet Street',
    'London',
    'EC4Y 1AA',
    TRUE,
    0
) ON CONFLICT (id) DO NOTHING;

-- ── Asset (new — no prior service history) ────────────────────────────────────
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES (
    '00000000-0000-7178-8000-000000000021',
    '00000000-0000-7178-8000-000000000011',
    'DHL-BOILER-001', 'SN-BOILER-2024', 'ThermoMax 3000', 'HeatTech Ltd', 'BOILER', TRUE, 0
) ON CONFLICT (id) DO NOTHING;

-- ── WO-THIN: R3 INSUFFICIENT ─────────────────────────────────────────────────
-- Has asset (passes R1), has fault description (passes R2),
-- BUT no faultCode AND no faultCategory AND no prior WOs (fails R3).
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7178-8000-000000000031',
    'WO178-031', 'IN_PROGRESS', 'NORMAL',
    '00000000-0000-7178-8000-000000000011',
    '00000000-0000-7178-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'Boiler not heating. Pilot light ignites but main burner fails to stay lit.',
    NULL, NULL,
    FALSE, 0, 0, 'PORTAL'
) ON CONFLICT (id) DO NOTHING;

-- ── WO-FULL: R1+R2+R3 SUFFICIENT (for positive-path stream test) ─────────────
-- Has asset (passes R1), has fault description AND code+category (passes R2+R3).
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7178-8000-000000000032',
    'WO178-032', 'IN_PROGRESS', 'HIGH',
    '00000000-0000-7178-8000-000000000011',
    '00000000-0000-7178-8000-000000000021',
    'cccccccc-0000-0000-0000-000000000001',
    'Main burner ignition failure — intermittent fault on cold start.',
    'BOILER-IGNITION', 'COMBUSTION',
    FALSE, 0, 0, 'DISPATCHER'
) ON CONFLICT (id) DO NOTHING;

-- ── WO-NOASSET: R1 INSUFFICIENT ──────────────────────────────────────────────
-- No asset linked — triggers R1_ASSET_UNRESOLVED.
INSERT INTO work_order (id, reference, state, priority, site_id,
                        asset_id, assigned_technician_id,
                        description, fault_code, fault_category,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES (
    '00000000-0000-7178-8000-000000000033',
    'WO178-033', 'NEW', 'LOW',
    '00000000-0000-7178-8000-000000000011',
    NULL,
    'cccccccc-0000-0000-0000-000000000001',
    'General fault reported by site manager.',
    NULL, NULL,
    FALSE, 0, 0, 'PORTAL'
) ON CONFLICT (id) DO NOTHING;
