-- audit-fixture.sql
-- Pre-requisite entities for audit integration tests.
-- Provides a customer, site, app_user, technician, and a work order that tests
-- can then mutate through JPA to build a multi-revision history via Envers.

-- Customer
INSERT INTO customer (id, name) VALUES
    ('cc000000-0000-7000-8000-000000000001', 'Audit Test Corp');

-- Site
INSERT INTO site (id, name, customer_id) VALUES
    ('55000000-0000-7000-8000-000000000001', 'Audit Test Site',
     'cc000000-0000-7000-8000-000000000001');

-- App user (used as the authenticated actor in tests)
INSERT INTO app_user (id, email, password_hash, full_name, active) VALUES
    ('aa000000-0000-7000-8000-000000000001', 'auditor@example.com',
     '$2a$10$placeholderhashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
     'Test Auditor', TRUE);

-- Technician linked to the app_user
INSERT INTO technician (id, app_user_id, employee_number) VALUES
    ('bb000000-0000-7000-8000-000000000001',
     'aa000000-0000-7000-8000-000000000001', 'EMP-AUDIT-01');

-- Work order — initial state before test mutations
INSERT INTO work_order (id, reference, state, priority, site_id, version) VALUES
    ('w0000000-0000-7000-8000-000000000001', 'WO-AUDIT-001', 'NEW', 'HIGH',
     '55000000-0000-7000-8000-000000000001', 0);
