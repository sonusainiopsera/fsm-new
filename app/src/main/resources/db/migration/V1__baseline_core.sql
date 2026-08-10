-- =============================================================================
-- V1: Baseline core schema for field-service-api
-- All primary keys are UUID (application-generated UUIDv7 for time-ordered locality).
-- Timestamps are TIMESTAMPTZ to avoid timezone ambiguity.
-- Data classification comments are machine-readable markers for retention tooling.
-- =============================================================================

-- ── Authentication & Identity ────────────────────────────────────────────────

CREATE TABLE app_user (
    id              UUID        PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    -- CONFIDENTIAL: BCrypt password hash. Column sized for 60-char BCrypt output
    -- plus a 12-char algorithm prefix headroom (e.g. {bcrypt}$2a$...). NEVER log.
    password_hash   VARCHAR(72) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

COMMENT ON COLUMN app_user.password_hash IS
    'BCrypt hash, exactly 60 characters. Column sized 72 for future algorithm prefix headroom. Never echo or log.';

CREATE TABLE role (
    id          UUID        PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    CONSTRAINT uq_role_name UNIQUE (name)
);

CREATE TABLE user_role (
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES role (id)     ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ── Customers & Sites ────────────────────────────────────────────────────────

-- CONFIDENTIAL: customer contact data — subject to retention policy.
CREATE TABLE customer (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    -- CONFIDENTIAL
    contact_email   VARCHAR(320),
    -- CONFIDENTIAL
    contact_phone   VARCHAR(50),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- CONFIDENTIAL: site address and GPS coordinates.
CREATE TABLE site (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    customer_id UUID         NOT NULL REFERENCES customer (id),
    -- CONFIDENTIAL
    address     VARCHAR(500),
    -- CONFIDENTIAL
    latitude    NUMERIC(9,6),
    -- CONFIDENTIAL
    longitude   NUMERIC(9,6),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE asset (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    site_id      UUID         NOT NULL REFERENCES site (id),
    asset_type   VARCHAR(100),
    serial_number VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Technicians & Certifications ─────────────────────────────────────────────

-- CONFIDENTIAL: technician contact data.
CREATE TABLE technician (
    id          UUID         PRIMARY KEY,
    user_id     UUID         REFERENCES app_user (id),
    full_name   VARCHAR(200) NOT NULL,
    -- CONFIDENTIAL
    email       VARCHAR(320),
    -- CONFIDENTIAL
    phone       VARCHAR(50),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE technician_certification (
    id              UUID         PRIMARY KEY,
    technician_id   UUID         NOT NULL REFERENCES technician (id),
    cert_type       VARCHAR(100) NOT NULL,
    issued_at       TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Work Orders & Assignments ────────────────────────────────────────────────

CREATE TABLE work_order (
    id                      UUID         PRIMARY KEY,
    title                   VARCHAR(300) NOT NULL,
    -- State vocabulary enforced by DB constraint — mirrors WorkOrderState enum.
    -- Any out-of-vocabulary insert, even from a migration script, is rejected.
    state                   VARCHAR(30)  NOT NULL DEFAULT 'NEW',
    site_id                 UUID         NOT NULL REFERENCES site (id),
    -- VARCHAR rather than FK so unassigned state (NULL) and external technician
    -- identifiers can coexist. Integrity enforced at the application layer.
    assigned_technician_id  VARCHAR(100),
    priority                VARCHAR(20),
    sla_deadline            TIMESTAMPTZ,
    description             TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Optimistic locking version column (mapped via JPA @Version → Long → BIGINT).
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_work_order_state CHECK (state IN (
        'NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD',
        'COMPLETED', 'CLOSED', 'CANCELLED'))
);

CREATE TABLE assignment (
    id              UUID        PRIMARY KEY,
    work_order_id   UUID        NOT NULL REFERENCES work_order (id),
    -- String identifier matching the technician's auth-system user-id or
    -- the technician.user_id. Kept as VARCHAR for flexibility.
    technician_id   VARCHAR(100) NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Optimistic locking version column (mapped via JPA @Version → Long → BIGINT).
    version         BIGINT      NOT NULL DEFAULT 0
);

-- ── Parts & Inventory ────────────────────────────────────────────────────────

CREATE TABLE part (
    id              UUID         PRIMARY KEY,
    part_number     VARCHAR(50)  NOT NULL,
    description     VARCHAR(500),
    unit_cost       NUMERIC(10,2),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_part_number UNIQUE (part_number)
);

CREATE TABLE stock_location (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    site_id     UUID         REFERENCES site (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE stock_balance (
    id                  UUID     PRIMARY KEY,
    part_id             UUID     NOT NULL REFERENCES part (id),
    location_id         UUID     NOT NULL REFERENCES stock_location (id),
    -- Non-negative invariant: zero or positive stock only.
    -- Application uses a conditional UPDATE...WHERE quantity_on_hand >= :n to decrement.
    quantity_on_hand    INTEGER  NOT NULL DEFAULT 0,
    -- Optimistic locking version column (mapped via JPA @Version → Long → BIGINT).
    version             BIGINT   NOT NULL DEFAULT 0,
    CONSTRAINT chk_stock_balance_non_negative CHECK (quantity_on_hand >= 0),
    CONSTRAINT uq_stock_balance_part_location UNIQUE (part_id, location_id)
);

-- Append-only ledger: no application UPDATE or DELETE path.
-- stock_balance is the single source of truth for current levels;
-- stock_ledger is the immutable audit trail.
CREATE TABLE stock_ledger (
    id              UUID        PRIMARY KEY,
    part_id         UUID        NOT NULL REFERENCES part (id),
    work_order_id   UUID        REFERENCES work_order (id),
    -- ISSUED, RETURNED, ADJUSTMENT, RECEIPT
    movement_type   VARCHAR(20) NOT NULL,
    quantity        INTEGER     NOT NULL,
    -- String technician id (mirrors assignment.technician_id convention).
    technician_id   VARCHAR(100),
    reference       VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
