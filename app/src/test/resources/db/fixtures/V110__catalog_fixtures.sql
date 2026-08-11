-- =============================================================================
-- V110: Catalog test fixtures — customers, sites, and assets
-- =============================================================================
-- Anonymised reference data with no real PII.
-- UUID ranges: cc000000-... for customers, dd000000-... for sites,
--              ee000000-... for assets to avoid collisions with V100 fixtures.
--
-- Scenarios covered:
--   - 3 customers: 2 active (ALPHA, BETA), 1 inactive (GAMMA)
--   - 8 sites: active and inactive, including one under the inactive customer
--   - 20 assets: active and inactive; includes one asset on a deactivated site
--   - Partial unique index verification: same account_code on inactive customer OK
-- =============================================================================

-- ── Customers ─────────────────────────────────────────────────────────────────

INSERT INTO customer (id, name, account_code, legal_name, primary_contact_name,
                      primary_contact_email, is_active, version)
VALUES
    -- ALPHA Corp — active, has 4 sites
    ('cc000000-0000-0000-0000-000000000001',
     'Alpha Corp',
     'ALPHA-001',
     'Alpha Corporation Ltd',
     'Alice Alpha',
     'alice@alpha-corp.example',
     true,
     0),

    -- BETA Services — active, has 3 sites
    ('cc000000-0000-0000-0000-000000000002',
     'Beta Services',
     'BETA-001',
     'Beta Services Plc',
     'Bob Beta',
     'bob@beta-services.example',
     true,
     0),

    -- GAMMA Inc — inactive (relationship ended), has 1 inactive site
    ('cc000000-0000-0000-0000-000000000003',
     'Gamma Inc',
     'GAMMA-001',
     'Gamma Incorporated',
     'Carol Gamma',
     'carol@gamma-inc.example',
     false,
     0);

UPDATE customer SET deactivated_at = now(), relationship_ended_on = '2024-06-30'
WHERE id = 'cc000000-0000-0000-0000-000000000003';

-- ── Sites ─────────────────────────────────────────────────────────────────────

INSERT INTO site (id, customer_id, name, site_code, display_name, address, postcode,
                  is_active, version)
VALUES
    -- ALPHA sites (4)
    ('dd000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000001',
     'Alpha HQ',
     'ALPHA-HQ',
     'Alpha Headquarters',
     '1 Alpha Street, London',
     'EC1A 1AA',
     true,
     0),
    ('dd000000-0000-0000-0000-000000000002',
     'cc000000-0000-0000-0000-000000000001',
     'Alpha Warehouse',
     'ALPHA-WH',
     'Alpha Warehouse East',
     '2 Warehouse Road, Manchester',
     'M1 1AA',
     true,
     0),
    ('dd000000-0000-0000-0000-000000000003',
     'cc000000-0000-0000-0000-000000000001',
     'Alpha Data Centre',
     'ALPHA-DC',
     'Alpha Data Centre North',
     '3 Server Lane, Leeds',
     'LS1 1AA',
     true,
     0),
    ('dd000000-0000-0000-0000-000000000004',
     'cc000000-0000-0000-0000-000000000001',
     'Alpha Retail',
     'ALPHA-RT',
     'Alpha Retail Park',
     '4 Retail Avenue, Birmingham',
     'B1 1AA',
     false,  -- inactive site
     0),

    -- BETA sites (3)
    ('dd000000-0000-0000-0000-000000000005',
     'cc000000-0000-0000-0000-000000000002',
     'Beta Office',
     'BETA-OFF',
     'Beta Main Office',
     '5 Beta Boulevard, Bristol',
     'BS1 1AA',
     true,
     0),
    ('dd000000-0000-0000-0000-000000000006',
     'cc000000-0000-0000-0000-000000000002',
     'Beta Plant',
     'BETA-PLT',
     'Beta Manufacturing Plant',
     '6 Plant Street, Sheffield',
     'S1 1AA',
     true,
     0),
    ('dd000000-0000-0000-0000-000000000007',
     'cc000000-0000-0000-0000-000000000002',
     'Beta Depot',
     'BETA-DPT',
     'Beta Northern Depot',
     '7 Depot Road, Newcastle',
     'NE1 1AA',
     true,
     0),

    -- GAMMA site (1, deactivated with customer)
    ('dd000000-0000-0000-0000-000000000008',
     'cc000000-0000-0000-0000-000000000003',
     'Gamma Office',
     'GAMMA-OFF',
     'Gamma Head Office',
     '8 Gamma Way, Edinburgh',
     'EH1 1AA',
     false,
     0);

UPDATE site SET deactivated_at = now()
WHERE id IN ('dd000000-0000-0000-0000-000000000004',
             'dd000000-0000-0000-0000-000000000008');

-- ── Assets ────────────────────────────────────────────────────────────────────
-- 20 assets across sites; includes inactive examples and one asset on a deactivated site.

INSERT INTO asset (id, site_id, name, asset_tag, manufacturer, model, category,
                   is_active, version)
VALUES
    -- Alpha HQ assets (5)
    ('ee000000-0000-0000-0000-000000000001',
     'dd000000-0000-0000-0000-000000000001',
     'Rooftop HVAC Unit 1',
     'ALPHA-HQ-HVAC-001',
     'Carrier',
     'AquaForce 30XV',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000002',
     'dd000000-0000-0000-0000-000000000001',
     'Rooftop HVAC Unit 2',
     'ALPHA-HQ-HVAC-002',
     'Carrier',
     'AquaForce 30XV',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000003',
     'dd000000-0000-0000-0000-000000000001',
     'Main Electrical Panel',
     'ALPHA-HQ-ELEC-001',
     'ABB',
     'MNS 3.0',
     'ELECTRICAL',
     true, 0),
    ('ee000000-0000-0000-0000-000000000004',
     'dd000000-0000-0000-0000-000000000001',
     'Boiler 1',
     'ALPHA-HQ-BOIL-001',
     'Viessmann',
     'Vitocrossal 300',
     'PLUMBING',
     true, 0),
    ('ee000000-0000-0000-0000-000000000005',
     'dd000000-0000-0000-0000-000000000001',
     'Decommissioned UPS',
     'ALPHA-HQ-UPS-OLD',
     'APC',
     'Smart-UPS 3000',
     'ELECTRICAL',
     false, 0),  -- inactive asset

    -- Alpha Warehouse assets (4)
    ('ee000000-0000-0000-0000-000000000006',
     'dd000000-0000-0000-0000-000000000002',
     'Warehouse HVAC',
     'ALPHA-WH-HVAC-001',
     'Daikin',
     'Rooftop VRV',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000007',
     'dd000000-0000-0000-0000-000000000002',
     'Loading Bay Door Motor',
     'ALPHA-WH-DOOR-001',
     'Hormann',
     'SupraMatic H',
     'MECHANICAL',
     true, 0),
    ('ee000000-0000-0000-0000-000000000008',
     'dd000000-0000-0000-0000-000000000002',
     'Fire Suppression Panel',
     'ALPHA-WH-FIRE-001',
     'Notifier',
     'NFS2-3030',
     'FIRE_SAFETY',
     true, 0),
    ('ee000000-0000-0000-0000-000000000009',
     'dd000000-0000-0000-0000-000000000002',
     'Old Sprinkler Control',
     'ALPHA-WH-SPRNK-OLD',
     'Viking',
     'GP-01',
     'FIRE_SAFETY',
     false, 0),  -- inactive

    -- Alpha Data Centre assets (3)
    ('ee000000-0000-0000-0000-000000000010',
     'dd000000-0000-0000-0000-000000000003',
     'Precision Cooling Unit A',
     'ALPHA-DC-COOL-A',
     'Emerson',
     'Liebert PEX',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000011',
     'dd000000-0000-0000-0000-000000000003',
     'Precision Cooling Unit B',
     'ALPHA-DC-COOL-B',
     'Emerson',
     'Liebert PEX',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000012',
     'dd000000-0000-0000-0000-000000000003',
     'Generator',
     'ALPHA-DC-GEN-001',
     'Caterpillar',
     'XQE500',
     'POWER',
     true, 0),

    -- Beta Office assets (3)
    ('ee000000-0000-0000-0000-000000000013',
     'dd000000-0000-0000-0000-000000000005',
     'Office HVAC System',
     'BETA-OFF-HVAC-001',
     'Mitsubishi',
     'City Multi VRF',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000014',
     'dd000000-0000-0000-0000-000000000005',
     'Server Room Cooling',
     'BETA-OFF-COOL-001',
     'Schneider',
     'NetShelter CX',
     'HVAC',
     true, 0),
    ('ee000000-0000-0000-0000-000000000015',
     'dd000000-0000-0000-0000-000000000005',
     'Lift System',
     'BETA-OFF-LIFT-001',
     'Schindler',
     '3300 MRL',
     'MECHANICAL',
     true, 0),

    -- Beta Plant assets (3)
    ('ee000000-0000-0000-0000-000000000016',
     'dd000000-0000-0000-0000-000000000006',
     'Compressor A',
     'BETA-PLT-COMP-A',
     'Atlas Copco',
     'GA110',
     'MECHANICAL',
     true, 0),
    ('ee000000-0000-0000-0000-000000000017',
     'dd000000-0000-0000-0000-000000000006',
     'Compressor B',
     'BETA-PLT-COMP-B',
     'Atlas Copco',
     'GA110',
     'MECHANICAL',
     true, 0),
    ('ee000000-0000-0000-0000-000000000018',
     'dd000000-0000-0000-0000-000000000006',
     'Overhead Crane',
     'BETA-PLT-CRANE-001',
     'Street Crane',
     'SX-EOT',
     'MECHANICAL',
     true, 0),

    -- Alpha Retail (inactive site) — asset whose site was deactivated
    ('ee000000-0000-0000-0000-000000000019',
     'dd000000-0000-0000-0000-000000000004',
     'Retail HVAC',
     'ALPHA-RT-HVAC-001',
     'Lennox',
     'XC21',
     'HVAC',
     false, 0),  -- deactivated with the site

    -- Gamma site (inactive) — one asset
    ('ee000000-0000-0000-0000-000000000020',
     'dd000000-0000-0000-0000-000000000008',
     'Gamma Office HVAC',
     'GAMMA-OFF-HVAC-001',
     'Trane',
     'Intellipak',
     'HVAC',
     false, 0);  -- deactivated with the site/customer

UPDATE asset SET deactivated_at = now()
WHERE id IN (
    'ee000000-0000-0000-0000-000000000005',
    'ee000000-0000-0000-0000-000000000009',
    'ee000000-0000-0000-0000-000000000019',
    'ee000000-0000-0000-0000-000000000020'
);
