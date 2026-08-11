-- =============================================================================
-- V5: Envers audit tables — created by Flyway so Hibernate ddl-auto=validate succeeds.
-- All audit tables use a composite (id, rev) primary key.
-- Mirrored columns are all nullable in audit tables regardless of source nullability.
-- password_hash is intentionally absent from app_user_aud (Restricted classification).
-- =============================================================================

-- Revision sequence (consumed by AppRevisionEntity via @SequenceGenerator allocationSize=1)
CREATE SEQUENCE revinfo_seq START WITH 1 INCREMENT BY 1 NO MAXVALUE NO CYCLE;

-- Extended REVINFO table with actor attribution columns
CREATE TABLE revinfo (
    rev             BIGINT       NOT NULL DEFAULT nextval('revinfo_seq') PRIMARY KEY,
    revtstmp        BIGINT       NOT NULL,
    actor_user_id   VARCHAR(255),
    actor_role      VARCHAR(100),
    trace_id        VARCHAR(36),
    client_ip       VARCHAR(45)
);

-- ── work_order_aud ────────────────────────────────────────────────────────────

CREATE TABLE work_order_aud (
    id                      UUID        NOT NULL,
    rev                     BIGINT      NOT NULL,
    revtype                 SMALLINT,
    title                   VARCHAR(300),
    state                   VARCHAR(30),
    site_id                 UUID,
    assigned_technician_id  VARCHAR(100),
    priority                VARCHAR(20),
    created_at              TIMESTAMPTZ,
    version                 BIGINT,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- BRIN index on rev: audit table is append-only and naturally ordered by rev,
-- making BRIN far cheaper than B-tree at high row counts.
CREATE INDEX idx_work_order_aud_rev ON work_order_aud USING BRIN (rev);

-- ── assignment_aud ────────────────────────────────────────────────────────────

CREATE TABLE assignment_aud (
    id              UUID        NOT NULL,
    rev             BIGINT      NOT NULL,
    revtype         SMALLINT,
    work_order_id   UUID,
    technician_id   VARCHAR(100),
    assigned_at     TIMESTAMPTZ,
    is_active       BOOLEAN,
    version         BIGINT,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ── technician_certification_aud ──────────────────────────────────────────────

CREATE TABLE technician_certification_aud (
    id              UUID        NOT NULL,
    rev             BIGINT      NOT NULL,
    revtype         SMALLINT,
    technician_id   UUID,
    cert_type       VARCHAR(100),
    issued_at       TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ── app_user_aud ──────────────────────────────────────────────────────────────
-- password_hash is intentionally excluded (Restricted-classified).

CREATE TABLE app_user_aud (
    id          UUID        NOT NULL,
    rev         BIGINT      NOT NULL,
    revtype     SMALLINT,
    email       VARCHAR(320),
    full_name   VARCHAR(200),
    is_active   BOOLEAN,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ── site_aud ──────────────────────────────────────────────────────────────────

CREATE TABLE site_aud (
    id          UUID        NOT NULL,
    rev         BIGINT      NOT NULL,
    revtype     SMALLINT,
    name        VARCHAR(200),
    customer_id UUID,
    address     VARCHAR(500),
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ── sla_policy_aud ────────────────────────────────────────────────────────────

CREATE TABLE sla_policy_aud (
    id                  UUID        NOT NULL,
    rev                 BIGINT      NOT NULL,
    revtype             SMALLINT,
    priority            VARCHAR(20),
    response_minutes    INTEGER,
    resolution_minutes  INTEGER,
    at_risk_fraction    NUMERIC(3,2),
    effective_from      TIMESTAMPTZ,
    effective_to        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);
