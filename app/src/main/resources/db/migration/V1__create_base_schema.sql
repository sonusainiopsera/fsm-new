-- V1: Base schema for field-service-api
-- Covers: sites, work_orders, assignments, assets, stock_movements

CREATE TABLE sites (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    customer_account_id UUID NOT NULL,
    address             VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sites_customer_account_id ON sites (customer_account_id);

CREATE TABLE work_orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title                   VARCHAR(300) NOT NULL,
    state                   VARCHAR(30)  NOT NULL DEFAULT 'NEW',
    site_id                 UUID NOT NULL REFERENCES sites (id),
    assigned_technician_id  VARCHAR(100),
    priority                VARCHAR(20),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_work_order_state CHECK (state IN (
        'NEW','ASSIGNED','EN_ROUTE','IN_PROGRESS','ON_HOLD','COMPLETED','CANCELLED'))
);

CREATE INDEX idx_work_orders_assigned_technician ON work_orders (assigned_technician_id)
    WHERE assigned_technician_id IS NOT NULL;
CREATE INDEX idx_work_orders_site_id ON work_orders (site_id);
CREATE INDEX idx_work_orders_state ON work_orders (state);

CREATE TABLE assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id   UUID NOT NULL REFERENCES work_orders (id),
    technician_id   VARCHAR(100) NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_assignments_technician ON assignments (technician_id);
CREATE INDEX idx_assignments_work_order ON assignments (work_order_id);

CREATE TABLE assets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    site_id     UUID NOT NULL REFERENCES sites (id),
    asset_type  VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_assets_site_id ON assets (site_id);

CREATE TABLE stock_movements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id   UUID NOT NULL REFERENCES work_orders (id),
    part_number     VARCHAR(100) NOT NULL,
    quantity        INTEGER NOT NULL,
    technician_id   VARCHAR(100) NOT NULL,
    moved_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_movements_technician ON stock_movements (technician_id);
CREATE INDEX idx_stock_movements_work_order ON stock_movements (work_order_id);
