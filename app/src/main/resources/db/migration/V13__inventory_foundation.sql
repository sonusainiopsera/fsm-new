-- V13__inventory_foundation.sql
-- Expand-only migration: adds required columns/constraints to inventory tables,
-- creates Envers AUD tables for part and stock_location, and indexes.
-- No columns are renamed or removed; all new NOT NULL columns carry DEFAULT values.

-- ============================================================
-- part — additional catalogue columns
-- ============================================================
ALTER TABLE part
    ADD COLUMN IF NOT EXISTS unit_of_measure  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS reorder_point    INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reorder_quantity INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS active           BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS updated_at       TIMESTAMPTZ;

ALTER TABLE part
    ADD CONSTRAINT chk_part_reorder_point_nonneg  CHECK (reorder_point    >= 0) NOT VALID,
    ADD CONSTRAINT chk_part_reorder_qty_nonneg    CHECK (reorder_quantity >= 0) NOT VALID;

ALTER TABLE part VALIDATE CONSTRAINT chk_part_reorder_point_nonneg;
ALTER TABLE part VALIDATE CONSTRAINT chk_part_reorder_qty_nonneg;

-- ============================================================
-- stock_location — location type and technician ownership
-- ============================================================
ALTER TABLE stock_location
    ADD COLUMN IF NOT EXISTS location_type VARCHAR(20)  NOT NULL DEFAULT 'WAREHOUSE',
    ADD COLUMN IF NOT EXISTS technician_id UUID,
    ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMPTZ;

-- VEHICLE location must have a technician; WAREHOUSE must not.
ALTER TABLE stock_location
    ADD CONSTRAINT chk_stock_loc_vehicle_tech CHECK (
        (location_type = 'VEHICLE'   AND technician_id IS NOT NULL) OR
        (location_type = 'WAREHOUSE' AND technician_id IS NULL)
    ) NOT VALID;

ALTER TABLE stock_location VALIDATE CONSTRAINT chk_stock_loc_vehicle_tech;

-- ============================================================
-- stock_balance — reserved quantity and timestamp
-- ============================================================
ALTER TABLE stock_balance
    ADD COLUMN IF NOT EXISTS quantity_reserved INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ;

ALTER TABLE stock_balance
    ADD CONSTRAINT chk_stock_reserved_nonneg CHECK (quantity_reserved >= 0) NOT VALID;

ALTER TABLE stock_balance VALIDATE CONSTRAINT chk_stock_reserved_nonneg;

-- ============================================================
-- Indexes for stock_balance
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_stock_balance_part_loc ON stock_balance (part_id, location_id);
CREATE INDEX IF NOT EXISTS idx_stock_balance_loc      ON stock_balance (location_id);

-- ============================================================
-- Additional seed rows for expanded inventory (new V13 data)
-- ============================================================
-- Extra parts (10 more, total 13)
INSERT INTO part (id, part_number, name, unit_of_measure, reorder_point, reorder_quantity, active) VALUES
    ('ffffffff-0000-7007-8000-000000000004', 'BRK-P200', 'Pressure Break Switch',   'EACH',   5, 10, TRUE),
    ('ffffffff-0000-7007-8000-000000000005', 'FLT-002',  'Carbon Pre-Filter',       'EACH',  10, 20, TRUE),
    ('ffffffff-0000-7007-8000-000000000006', 'MTR-AC1',  'AC Fan Motor 230V',       'EACH',   2,  5, TRUE),
    ('ffffffff-0000-7007-8000-000000000007', 'CAP-450V',  '450V Run Capacitor',     'EACH',   5, 15, TRUE),
    ('ffffffff-0000-7007-8000-000000000008', 'LUB-OIL1', 'Compressor Oil 1L',       'LITRE', 10, 24, TRUE),
    ('ffffffff-0000-7007-8000-000000000009', 'VLV-EXP2', 'Expansion Valve 2-ton',   'EACH',   3,  6, TRUE),
    ('ffffffff-0000-7007-8000-000000000010', 'SNS-TMP1', 'Temperature Sensor NTC',  'EACH',  10, 25, TRUE),
    ('ffffffff-0000-7007-8000-000000000011', 'BRK-T100', 'Thermal Overload Breaker','EACH',   5, 10, TRUE),
    ('ffffffff-0000-7007-8000-000000000012', 'BLT-600',  'Drive Belt 600mm',        'EACH',   5, 10, TRUE),
    ('ffffffff-0000-7007-8000-000000000013', 'REF-410A', 'R410A Refrigerant Cylinder','EACH', 2,  4, TRUE);

-- Backfill unit_of_measure on original V4 parts
UPDATE part SET unit_of_measure = 'EACH'  WHERE id = 'ffffffff-0000-7007-8000-000000000001';
UPDATE part SET unit_of_measure = 'EACH'  WHERE id = 'ffffffff-0000-7007-8000-000000000002';
UPDATE part SET unit_of_measure = 'EACH'  WHERE id = 'ffffffff-0000-7007-8000-000000000003';

-- Additional warehouse locations (2 more)
INSERT INTO stock_location (id, name, location_type) VALUES
    ('ffffffff-0000-7008-8000-000000000003', 'North Depot',    'WAREHOUSE'),
    ('ffffffff-0000-7008-8000-000000000004', 'South Depot',    'WAREHOUSE'),
    ('ffffffff-0000-7008-8000-000000000005', 'East Depot',     'WAREHOUSE');

-- Vehicle locations (5 technician vans)
-- Technician IDs match V4 seed technicians; additional technicians are fictional test UUIDs
INSERT INTO stock_location (id, name, location_type, technician_id) VALUES
    ('ffffffff-0000-7008-8000-000000000006', 'Van Stock - Carol',   'VEHICLE', 'ffffffff-0000-7005-8000-000000000002'),
    ('ffffffff-0000-7008-8000-000000000007', 'Van Stock - Dave',    'VEHICLE', 'ffffffff-0000-7005-8000-000000000003'),
    ('ffffffff-0000-7008-8000-000000000008', 'Van Stock - Eve',     'VEHICLE', 'ffffffff-0000-7005-8000-000000000004'),
    ('ffffffff-0000-7008-8000-000000000009', 'Van Stock - Frank',   'VEHICLE', 'ffffffff-0000-7005-8000-000000000005'),
    ('ffffffff-0000-7008-8000-000000000010', 'Van Stock - Grace',   'VEHICLE', 'ffffffff-0000-7005-8000-000000000006');

-- Update existing Van Stock - Bob to be VEHICLE type with technician
UPDATE stock_location
   SET location_type = 'VEHICLE', technician_id = 'ffffffff-0000-7005-8000-000000000001'
 WHERE id = 'ffffffff-0000-7008-8000-000000000002';

-- Additional balances for new parts across locations
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES
    ('ffffffff-0000-7009-8000-000000000005',
     'ffffffff-0000-7007-8000-000000000004', 'ffffffff-0000-7008-8000-000000000001', 20),
    ('ffffffff-0000-7009-8000-000000000006',
     'ffffffff-0000-7007-8000-000000000005', 'ffffffff-0000-7008-8000-000000000001', 30),
    ('ffffffff-0000-7009-8000-000000000007',
     'ffffffff-0000-7007-8000-000000000006', 'ffffffff-0000-7008-8000-000000000003', 8),
    ('ffffffff-0000-7009-8000-000000000008',
     'ffffffff-0000-7007-8000-000000000007', 'ffffffff-0000-7008-8000-000000000003', 12),
    ('ffffffff-0000-7009-8000-000000000009',
     'ffffffff-0000-7007-8000-000000000008', 'ffffffff-0000-7008-8000-000000000001', 24),
    ('ffffffff-0000-7009-8000-000000000010',
     'ffffffff-0000-7007-8000-000000000004', 'ffffffff-0000-7008-8000-000000000006', 4),
    ('ffffffff-0000-7009-8000-000000000011',
     'ffffffff-0000-7007-8000-000000000005', 'ffffffff-0000-7008-8000-000000000006', 3);

-- ============================================================
-- Envers audit tables: part_aud and stock_location_aud
-- REVINFO and revinfo_seq already created in V5.
-- ============================================================
CREATE TABLE part_aud (
    id               UUID        NOT NULL,
    REV              INTEGER     NOT NULL,
    REVTYPE          SMALLINT,
    part_number      VARCHAR(100),
    name             VARCHAR(255),
    description      VARCHAR(4000),
    unit_of_measure  VARCHAR(50),
    reorder_point    INTEGER,
    reorder_quantity INTEGER,
    active           BOOLEAN,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    CONSTRAINT pk_part_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_part_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX brin_part_aud_rev ON part_aud USING BRIN (REV);

CREATE TABLE stock_location_aud (
    id             UUID        NOT NULL,
    REV            INTEGER     NOT NULL,
    REVTYPE        SMALLINT,
    name           VARCHAR(255),
    location_type  VARCHAR(20),
    technician_id  UUID,
    site_id        UUID,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    CONSTRAINT pk_stock_location_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_stock_location_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX brin_stock_location_aud_rev ON stock_location_aud USING BRIN (REV);
