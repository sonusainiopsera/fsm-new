-- V4__seed_reference_data.sql
-- Seed reference data: role records and representative domain objects
-- for downstream story tests and local development.
-- All IDs use the fixed UUID prefix 00000002-* to avoid collision with test fixtures.

-- =============================================================================
-- Roles (role table seed)
-- =============================================================================
INSERT INTO role (id, name) VALUES
    ('00000002-0000-7000-8000-000000000001', 'DISPATCHER'),
    ('00000002-0000-7000-8000-000000000002', 'ADMIN'),
    ('00000002-0000-7000-8000-000000000003', 'MANAGER'),
    ('00000002-0000-7000-8000-000000000004', 'TECHNICIAN'),
    ('00000002-0000-7000-8000-000000000005', 'CUSTOMER');

-- =============================================================================
-- Sample parts catalogue (for inventory tests)
-- =============================================================================
INSERT INTO part (id, sku, name, unit) VALUES
    ('00000002-0000-7000-8000-000000000101', 'PART-HVAC-FILTER-1',  'HVAC Air Filter 20x20',  'EACH'),
    ('00000002-0000-7000-8000-000000000102', 'PART-HVAC-BELT-A',    'HVAC Drive Belt Type A', 'EACH'),
    ('00000002-0000-7000-8000-000000000103', 'PART-ELEC-FUSE-20A',  'Fuse 20A',               'EACH'),
    ('00000002-0000-7000-8000-000000000104', 'PART-PLUMB-GASKET-1', 'Pipe Gasket 1-inch',     'EACH');
