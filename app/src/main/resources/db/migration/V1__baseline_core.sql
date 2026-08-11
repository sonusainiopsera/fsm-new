-- V1__baseline_core.sql
-- Full baseline schema for the Field Service Operations Platform.
-- All primary keys are UUID (generated as UUIDv7 in the application layer for B-tree locality).
-- Data-classification comments mark Confidential columns for retention/purge tooling.
-- All timestamps are TIMESTAMPTZ to avoid timezone drift in deadline arithmetic.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- app_user: authenticated principals for all roles.
-- password_hash is varchar(72) to comfortably hold a 60-char BCrypt output
-- (BCrypt current format: $2a$<cost>$<22-char salt><31-char hash> = 60 chars).
-- Wider than needed today so a future algorithm prefix never silently truncates.
-- =============================================================================
CREATE TABLE app_user (
    id              UUID          NOT NULL PRIMARY KEY,
    email           VARCHAR(320)  NOT NULL,  -- CONFIDENTIAL: email is PII
    password_hash   VARCHAR(72)   NOT NULL,  -- CONFIDENTIAL: credential, never logged
    display_name    VARCHAR(255)  NOT NULL,
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT uq_app_user_email UNIQUE (email)
);

COMMENT ON COLUMN app_user.email         IS 'CONFIDENTIAL – PII, mask in logs, anonymise in non-production';
COMMENT ON COLUMN app_user.password_hash IS 'CONFIDENTIAL – BCrypt hash, never log, never export';
COMMENT ON COLUMN app_user.password_hash IS 'Width 72 to hold 60-char BCrypt output with headroom for future algorithm prefix';

-- =============================================================================
-- role: role names granted to users (DISPATCHER, ADMIN, MANAGER, TECHNICIAN, CUSTOMER).
-- =============================================================================
CREATE TABLE role (
    id      UUID          NOT NULL PRIMARY KEY,
    name    VARCHAR(50)   NOT NULL,

    CONSTRAINT uq_role_name UNIQUE (name),
    CONSTRAINT chk_role_name CHECK (
        name IN ('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')
    )
);

-- =============================================================================
-- user_role: many-to-many join between users and roles.
-- =============================================================================
CREATE TABLE user_role (
    user_id     UUID  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id     UUID  NOT NULL REFERENCES role(id),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_role_user_id ON user_role(user_id);
CREATE INDEX idx_user_role_role_id ON user_role(role_id);

-- =============================================================================
-- customer: a business entity that owns one or more sites.
-- customer.id is the row-scope boundary used in the JWT customerAccountIds claim.
-- =============================================================================
CREATE TABLE customer (
    id              UUID          NOT NULL PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,
    contact_email   VARCHAR(320),             -- CONFIDENTIAL: customer PII
    contact_phone   VARCHAR(50),              -- CONFIDENTIAL: customer PII
    billing_address VARCHAR(500),             -- CONFIDENTIAL: customer PII
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         INTEGER       NOT NULL DEFAULT 0
);

COMMENT ON COLUMN customer.contact_email   IS 'CONFIDENTIAL – PII, mask in non-production';
COMMENT ON COLUMN customer.contact_phone   IS 'CONFIDENTIAL – PII, mask in non-production';
COMMENT ON COLUMN customer.billing_address IS 'CONFIDENTIAL – PII, mask in non-production';

-- =============================================================================
-- site: a physical location belonging to a customer where service is performed.
-- site.customer_id is the FK used for CUSTOMER row-scope enforcement.
-- =============================================================================
CREATE TABLE site (
    id          UUID          NOT NULL PRIMARY KEY,
    customer_id UUID          NOT NULL REFERENCES customer(id),
    name        VARCHAR(255)  NOT NULL,
    address     VARCHAR(500),               -- CONFIDENTIAL: location PII
    latitude    NUMERIC(9,6),               -- CONFIDENTIAL: location data
    longitude   NUMERIC(9,6),               -- CONFIDENTIAL: location data
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     INTEGER       NOT NULL DEFAULT 0
);

COMMENT ON COLUMN site.address   IS 'CONFIDENTIAL – site address, mask in non-production';
COMMENT ON COLUMN site.latitude  IS 'CONFIDENTIAL – geolocation, retain 90 days per policy';
COMMENT ON COLUMN site.longitude IS 'CONFIDENTIAL – geolocation, retain 90 days per policy';

CREATE INDEX idx_site_customer_id ON site(customer_id);

-- =============================================================================
-- asset: physical equipment at a site.
-- Scoped via site → customer for CUSTOMER principals.
-- =============================================================================
CREATE TABLE asset (
    id          UUID          NOT NULL PRIMARY KEY,
    site_id     UUID          NOT NULL REFERENCES site(id),
    name        VARCHAR(255)  NOT NULL,
    asset_type  VARCHAR(100),
    serial_no   VARCHAR(100),
    model       VARCHAR(255),
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX idx_asset_site_id ON asset(site_id);

-- =============================================================================
-- technician: field engineer profile linked to an app_user.
-- technician.id is the row-scope boundary for TECHNICIAN principals
-- (JWT technicianId claim).
-- =============================================================================
CREATE TABLE technician (
    id          UUID          NOT NULL PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES app_user(id),
    employee_no VARCHAR(50),
    phone       VARCHAR(50),               -- CONFIDENTIAL: staff contact PII
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT uq_technician_user_id UNIQUE (user_id)
);

COMMENT ON COLUMN technician.phone IS 'CONFIDENTIAL – staff PII, mask in non-production';

CREATE INDEX idx_technician_user_id ON technician(user_id);

-- =============================================================================
-- technician_certification: certifications held by a technician.
-- Used by the dispatch feasibility gate to enforce BR-01 (required certifications).
-- =============================================================================
CREATE TABLE technician_certification (
    id              UUID          NOT NULL PRIMARY KEY,
    technician_id   UUID          NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
    cert_type       VARCHAR(100)  NOT NULL,
    cert_reference  VARCHAR(100),
    issued_at       TIMESTAMPTZ   NOT NULL,
    expires_at      TIMESTAMPTZ,
    is_revoked      BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Composite index for the certification eligibility gate: given a technician,
-- find certifications of a type that haven't expired.
CREATE INDEX idx_technician_cert_lookup
    ON technician_certification(technician_id, expires_at)
    WHERE is_revoked = false;

-- =============================================================================
-- work_order: the central aggregate of the platform.
-- State vocabulary is enforced at the DB level so a migration or raw script
-- cannot insert an out-of-vocabulary state.
-- Version column enables optimistic locking via JPA @Version.
-- assigned_technician_id is the row-scope boundary for TECHNICIAN principals.
-- =============================================================================
CREATE TABLE work_order (
    id                      UUID          NOT NULL PRIMARY KEY,
    site_id                 UUID          NOT NULL REFERENCES site(id),
    asset_id                UUID          REFERENCES asset(id),
    customer_id             UUID          NOT NULL REFERENCES customer(id),
    assigned_technician_id  UUID          REFERENCES technician(id),
    state                   VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    priority                VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
    title                   VARCHAR(500),
    description             TEXT,
    sla_deadline            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 INTEGER       NOT NULL DEFAULT 0,

    -- State vocabulary: must mirror WorkOrderState enum in code.
    CONSTRAINT chk_work_order_state CHECK (
        state IN ('NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD',
                  'COMPLETED', 'CLOSED', 'CANCELLED')
    ),
    CONSTRAINT chk_work_order_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

CREATE INDEX idx_work_order_created_at_id        ON work_order(created_at DESC, id);
CREATE INDEX idx_work_order_state                ON work_order(state);
CREATE INDEX idx_work_order_assigned_technician  ON work_order(assigned_technician_id);
CREATE INDEX idx_work_order_site_id              ON work_order(site_id);
CREATE INDEX idx_work_order_customer_id          ON work_order(customer_id);
CREATE INDEX idx_work_order_sla_deadline         ON work_order(sla_deadline)
    WHERE state NOT IN ('COMPLETED', 'CLOSED', 'CANCELLED');

-- =============================================================================
-- assignment: append-only history of technician assignments to work orders.
-- is_current=true marks the active assignment.
-- Version column for optimistic locking.
-- =============================================================================
CREATE TABLE assignment (
    id              UUID          NOT NULL PRIMARY KEY,
    work_order_id   UUID          NOT NULL REFERENCES work_order(id),
    technician_id   UUID          NOT NULL REFERENCES technician(id),
    assigned_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    unassigned_at   TIMESTAMPTZ,
    is_current      BOOLEAN       NOT NULL DEFAULT true,
    notes           TEXT,
    version         INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX idx_assignment_work_order_id ON assignment(work_order_id);
CREATE INDEX idx_assignment_technician_id ON assignment(technician_id);
CREATE INDEX idx_assignment_current       ON assignment(work_order_id) WHERE is_current = true;

-- =============================================================================
-- part: parts catalogue reference data.
-- =============================================================================
CREATE TABLE part (
    id          UUID          NOT NULL PRIMARY KEY,
    sku         VARCHAR(100)  NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    unit        VARCHAR(50)   NOT NULL DEFAULT 'EACH',
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_part_sku UNIQUE (sku)
);

-- =============================================================================
-- stock_location: a named location where parts are held (e.g., a technician's van
-- or a warehouse shelf).
-- =============================================================================
CREATE TABLE stock_location (
    id              UUID          NOT NULL PRIMARY KEY,
    technician_id   UUID          REFERENCES technician(id),  -- null = warehouse location
    name            VARCHAR(255)  NOT NULL,
    location_type   VARCHAR(50)   NOT NULL DEFAULT 'VAN',
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_stock_location_type CHECK (
        location_type IN ('VAN', 'WAREHOUSE', 'SITE')
    )
);

CREATE INDEX idx_stock_location_technician_id ON stock_location(technician_id);

-- =============================================================================
-- stock_balance: current quantity on hand per part per location.
-- The non-negative CHECK constraint enforces BR-16 (zero negative inventory).
-- Unique constraint prevents duplicate balance rows per part+location.
-- Version column for optimistic locking on the conditional-decrement path.
-- =============================================================================
CREATE TABLE stock_balance (
    id                UUID     NOT NULL PRIMARY KEY,
    part_id           UUID     NOT NULL REFERENCES part(id),
    location_id       UUID     NOT NULL REFERENCES stock_location(id),
    quantity_on_hand  INTEGER  NOT NULL DEFAULT 0,
    version           INTEGER  NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_stock_balance_part_location UNIQUE (part_id, location_id),
    CONSTRAINT chk_stock_balance_non_negative CHECK (quantity_on_hand >= 0)
);

CREATE INDEX idx_stock_balance_part_id     ON stock_balance(part_id);
CREATE INDEX idx_stock_balance_location_id ON stock_balance(location_id);

-- =============================================================================
-- stock_ledger: append-only journal of all stock movements.
-- No application UPDATE or DELETE path; rows are immutable once written.
-- The conditional-update pattern is: UPDATE stock_balance SET qty = qty - n WHERE qty >= n.
-- =============================================================================
CREATE TABLE stock_ledger (
    id              UUID          NOT NULL PRIMARY KEY,
    part_id         UUID          NOT NULL REFERENCES part(id),
    location_id     UUID          NOT NULL REFERENCES stock_location(id),
    work_order_id   UUID          REFERENCES work_order(id),
    technician_id   UUID          REFERENCES technician(id),
    quantity_delta  INTEGER       NOT NULL,  -- positive = in, negative = out
    movement_type   VARCHAR(50)   NOT NULL,
    reference_no    VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_stock_ledger_movement_type CHECK (
        movement_type IN ('CONSUMPTION', 'RETURN', 'REPLENISHMENT', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN')
    )
);

CREATE INDEX idx_stock_ledger_part_created     ON stock_ledger(part_id, created_at DESC);
CREATE INDEX idx_stock_ledger_location_id      ON stock_ledger(location_id);
CREATE INDEX idx_stock_ledger_work_order_id    ON stock_ledger(work_order_id);
CREATE INDEX idx_stock_ledger_technician_id    ON stock_ledger(technician_id);
