-- V1__baseline_core.sql
-- Baseline schema for field-service-api.
-- All timestamps are TIMESTAMPTZ (UTC); all PKs are UUID generated in Java (UUIDv7).
-- password_hash is VARCHAR(72) — wide enough for BCrypt (60 chars) plus algorithm prefix headroom.

-- ============================================================
-- Users and roles
-- ============================================================
CREATE TABLE app_user (
    id            UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    -- CONFIDENTIAL: BCrypt hash; 72 chars accommodates BCrypt (60) plus future algorithm prefix
    password_hash VARCHAR(72)  NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user      PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE role (
    id   UUID        NOT NULL,
    name VARCHAR(50) NOT NULL,
    CONSTRAINT pk_role      PRIMARY KEY (id),
    CONSTRAINT uq_role_name UNIQUE (name)
);

CREATE TABLE user_role (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_user_role      PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role (id)
);

-- ============================================================
-- Customers and sites
-- ============================================================
CREATE TABLE customer (
    id            UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    -- CONFIDENTIAL: customer contact details — data classification Confidential
    contact_email VARCHAR(255),
    phone         VARCHAR(50),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer PRIMARY KEY (id)
);

CREATE TABLE site (
    id            UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    customer_id   UUID         NOT NULL,
    -- CONFIDENTIAL: physical address and geo-coordinates — data classification Confidential
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city          VARCHAR(100),
    postcode      VARCHAR(20),
    latitude      NUMERIC(9, 6),
    longitude     NUMERIC(9, 6),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_site          PRIMARY KEY (id),
    CONSTRAINT fk_site_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

CREATE TABLE asset (
    id            UUID         NOT NULL,
    site_id       UUID         NOT NULL,
    serial_number VARCHAR(100),
    model         VARCHAR(255),
    manufacturer  VARCHAR(255),
    installed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_asset      PRIMARY KEY (id),
    CONSTRAINT fk_asset_site FOREIGN KEY (site_id) REFERENCES site (id)
);

-- ============================================================
-- Technicians
-- ============================================================
CREATE TABLE technician (
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    full_name  VARCHAR(255) NOT NULL,
    -- CONFIDENTIAL: technician mobile number — data classification Confidential
    phone      VARCHAR(50),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_technician          PRIMARY KEY (id),
    CONSTRAINT fk_technician_user     FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_technician_user_id  UNIQUE (user_id)
);

CREATE TABLE technician_certification (
    id                 UUID        NOT NULL,
    technician_id      UUID        NOT NULL,
    certification_code VARCHAR(50) NOT NULL,
    issued_at          TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_technician_certification     PRIMARY KEY (id),
    CONSTRAINT fk_tech_cert_technician FOREIGN KEY (technician_id) REFERENCES technician (id)
);

-- ============================================================
-- Work orders and assignments
-- ============================================================
CREATE TABLE work_order (
    id                     UUID         NOT NULL,
    reference              VARCHAR(50)  NOT NULL,
    state                  VARCHAR(50)  NOT NULL,
    priority               VARCHAR(20)  NOT NULL,
    site_id                UUID         NOT NULL,
    asset_id               UUID,
    assigned_technician_id UUID,
    description            VARCHAR(4000),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version                INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_work_order        PRIMARY KEY (id),
    CONSTRAINT uq_work_order_ref    UNIQUE (reference),
    CONSTRAINT fk_work_order_site   FOREIGN KEY (site_id)                REFERENCES site (id),
    CONSTRAINT fk_work_order_asset  FOREIGN KEY (asset_id)               REFERENCES asset (id),
    CONSTRAINT fk_work_order_tech   FOREIGN KEY (assigned_technician_id) REFERENCES technician (id),
    CONSTRAINT chk_work_order_state CHECK (state IN (
        'NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED'
    )),
    CONSTRAINT chk_work_order_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE TABLE assignment (
    id            UUID        NOT NULL,
    work_order_id UUID        NOT NULL,
    technician_id UUID        NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version       INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_assignment      PRIMARY KEY (id),
    CONSTRAINT fk_assignment_wo   FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_assignment_tech FOREIGN KEY (technician_id) REFERENCES technician (id)
);

-- ============================================================
-- Inventory
-- ============================================================
CREATE TABLE part (
    id          UUID         NOT NULL,
    part_number VARCHAR(100) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(4000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_part        PRIMARY KEY (id),
    CONSTRAINT uq_part_number UNIQUE (part_number)
);

CREATE TABLE stock_location (
    id         UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    site_id    UUID,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_location      PRIMARY KEY (id),
    CONSTRAINT fk_stock_location_site FOREIGN KEY (site_id) REFERENCES site (id)
);

CREATE TABLE stock_balance (
    id               UUID    NOT NULL,
    part_id          UUID    NOT NULL,
    location_id      UUID    NOT NULL,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0,
    version          INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_balance           PRIMARY KEY (id),
    CONSTRAINT uq_stock_balance_part_loc  UNIQUE (part_id, location_id),
    CONSTRAINT fk_stock_balance_part      FOREIGN KEY (part_id)     REFERENCES part (id),
    CONSTRAINT fk_stock_balance_loc       FOREIGN KEY (location_id) REFERENCES stock_location (id),
    -- Inventory invariant: balance can never go negative
    CONSTRAINT chk_stock_non_negative     CHECK (quantity_on_hand >= 0)
);

-- Append-only ledger: no application UPDATE or DELETE path
CREATE TABLE stock_ledger (
    id              UUID        NOT NULL,
    part_id         UUID        NOT NULL,
    location_id     UUID        NOT NULL,
    quantity_change INTEGER     NOT NULL,
    reference       VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_ledger      PRIMARY KEY (id),
    CONSTRAINT fk_stock_ledger_part FOREIGN KEY (part_id)     REFERENCES part (id),
    CONSTRAINT fk_stock_ledger_loc  FOREIGN KEY (location_id) REFERENCES stock_location (id)
);
