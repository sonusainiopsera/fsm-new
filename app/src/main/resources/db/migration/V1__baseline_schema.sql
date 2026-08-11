-- V1__baseline_schema.sql
-- Baseline schema for the Field Service Operations Platform.
-- Includes all tables required for row-scope enforcement per WO-009.

-- Enable pgcrypto for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- Sites: customer locations where field service work is performed.
--        The customer_account_id is the row-scope boundary for CUSTOMER principals.
-- =============================================================================
CREATE TABLE site (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    address             VARCHAR(500),
    customer_account_id UUID        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_site_customer_account_id ON site(customer_account_id);

-- =============================================================================
-- Assets: physical equipment at a site.
--         Scoped via site.customer_account_id.
-- =============================================================================
CREATE TABLE asset (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    site_id     UUID        NOT NULL REFERENCES site(id),
    name        VARCHAR(255) NOT NULL,
    asset_type  VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_asset_site_id ON asset(site_id);

-- =============================================================================
-- Work Orders: the central aggregate.
--   - assigned_technician_id is the row-scope boundary for TECHNICIAN principals.
--   - site_id → site.customer_account_id is the row-scope boundary for CUSTOMER principals.
--   - Optimistic locking via @Version (version column).
-- =============================================================================
CREATE TABLE work_order (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    site_id                 UUID        NOT NULL REFERENCES site(id),
    asset_id                UUID        REFERENCES asset(id),
    assigned_technician_id  UUID,
    state                   VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    priority                VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    description             TEXT,
    sla_deadline            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT chk_work_order_state CHECK (
        state IN ('OPEN', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT chk_work_order_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

CREATE INDEX idx_work_order_site_id                ON work_order(site_id);
CREATE INDEX idx_work_order_assigned_technician_id ON work_order(assigned_technician_id);
CREATE INDEX idx_work_order_state                  ON work_order(state);
CREATE INDEX idx_work_order_sla_deadline            ON work_order(sla_deadline) WHERE state NOT IN ('COMPLETED', 'CANCELLED');

-- =============================================================================
-- Assignments: append-only assignment history.
--   - is_current=true marks the active assignment.
-- =============================================================================
CREATE TABLE assignment (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    work_order_id   UUID        NOT NULL REFERENCES work_order(id),
    technician_id   UUID        NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    unassigned_at   TIMESTAMPTZ,
    is_current      BOOLEAN     NOT NULL DEFAULT true
);

CREATE INDEX idx_assignment_work_order_id ON assignment(work_order_id);
CREATE INDEX idx_assignment_technician_id ON assignment(technician_id);
CREATE INDEX idx_assignment_current       ON assignment(work_order_id) WHERE is_current = true;

-- =============================================================================
-- Stock Movements: append-only inventory ledger per technician.
-- =============================================================================
CREATE TABLE stock_movement (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    technician_id   UUID        NOT NULL,
    work_order_id   UUID        REFERENCES work_order(id),
    item_id         UUID        NOT NULL,
    quantity        INTEGER     NOT NULL,
    movement_type   VARCHAR(50) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_stock_movement_type CHECK (
        movement_type IN ('CONSUMPTION', 'RETURN', 'REPLENISHMENT', 'ADJUSTMENT')
    )
);

CREATE INDEX idx_stock_movement_technician_id ON stock_movement(technician_id);
CREATE INDEX idx_stock_movement_work_order_id ON stock_movement(work_order_id);
