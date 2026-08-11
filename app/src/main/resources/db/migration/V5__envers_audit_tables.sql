-- =============================================================================
-- V5: Hibernate Envers audit tables
-- =============================================================================
-- Creates REVINFO, the revision sequence, all *_aud tables, and a BRIN index
-- on work_order_aud(rev). Envers validates these at startup; ddl-auto=validate
-- requires these tables to exist before the application starts.
--
-- CONSTRAINT: Hibernate must never create or alter these tables (ddl-auto=validate).
-- Version fields (@Version) are excluded per do_not_audit_optimistic_locking_field=true.
-- password_hash is excluded from app_user_aud per CONFIDENTIAL classification.
-- =============================================================================

-- Revision sequence — allocationSize=1 means INCREMENT BY 1 matches Hibernate usage
CREATE SEQUENCE IF NOT EXISTS revinfo_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- Extended revision info table — actor attribution required for SOC 2 / ISO 27001
CREATE TABLE IF NOT EXISTS revinfo (
    rev           INTEGER      NOT NULL DEFAULT nextval('revinfo_seq'),
    rev_tstmp     BIGINT       NOT NULL,
    actor_user_id VARCHAR(255),
    actor_role    VARCHAR(50),
    trace_id      VARCHAR(64),
    client_ip     VARCHAR(45),
    CONSTRAINT pk_revinfo PRIMARY KEY (rev)
);

ALTER SEQUENCE revinfo_seq OWNED BY revinfo.rev;

-- ---------------------------------------------------------------------------
-- work_order_aud
-- Mirrors work_order + BaseEntity columns; site_id and customer_id stored
-- as plain FK UUIDs from the @ManyToOne JoinColumn (NOT_AUDITED target mode).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order_aud (
    id                     UUID        NOT NULL,
    rev                    INTEGER     NOT NULL,
    revtype                SMALLINT    NOT NULL,
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ,
    site_id                UUID,
    customer_id            UUID,
    assigned_technician_id UUID,
    state                  VARCHAR(20),
    priority               VARCHAR(10),
    title                  VARCHAR(500),
    description            TEXT,
    sla_deadline           TIMESTAMPTZ,
    CONSTRAINT pk_work_order_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_work_order_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- BRIN index: append-only table naturally ordered by rev — far cheaper than B-tree
CREATE INDEX IF NOT EXISTS idx_work_order_aud_rev_brin
    ON work_order_aud USING brin (rev);

-- ---------------------------------------------------------------------------
-- app_user_aud  — password_hash intentionally absent (CONFIDENTIAL column)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user_aud (
    id           UUID        NOT NULL,
    rev          INTEGER     NOT NULL,
    revtype      SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    email        VARCHAR(320),
    display_name VARCHAR(255),
    is_active    BOOLEAN,
    CONSTRAINT pk_app_user_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_app_user_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- site_aud
-- customer_id stored as plain FK UUID from the @ManyToOne JoinColumn.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS site_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT    NOT NULL,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    customer_id UUID,
    name        VARCHAR(255),
    address     VARCHAR(500),
    latitude    NUMERIC(9, 6),
    longitude   NUMERIC(9, 6),
    is_active   BOOLEAN,
    CONSTRAINT pk_site_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_site_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- assignment_aud  — no BaseEntity (no created_at/updated_at); has assigned_at
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assignment_aud (
    id            UUID        NOT NULL,
    rev           INTEGER     NOT NULL,
    revtype       SMALLINT    NOT NULL,
    work_order_id UUID,
    technician_id UUID,
    assigned_at   TIMESTAMPTZ,
    unassigned_at TIMESTAMPTZ,
    is_current    BOOLEAN,
    notes         TEXT,
    CONSTRAINT pk_assignment_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_assignment_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- technician_certification_aud  — no BaseEntity, no version column
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technician_certification_aud (
    id             UUID        NOT NULL,
    rev            INTEGER     NOT NULL,
    revtype        SMALLINT    NOT NULL,
    technician_id  UUID,
    cert_type      VARCHAR(100),
    cert_reference VARCHAR(100),
    issued_at      TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ,
    is_revoked     BOOLEAN,
    created_at     TIMESTAMPTZ,
    CONSTRAINT pk_technician_certification_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_technician_certification_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- sla_policy_aud  — no BaseEntity, no version column
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sla_policy_aud (
    id                 UUID        NOT NULL,
    rev                INTEGER     NOT NULL,
    revtype            SMALLINT    NOT NULL,
    priority           VARCHAR(10),
    response_minutes   INTEGER,
    resolution_minutes INTEGER,
    at_risk_fraction   NUMERIC(3, 2),
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    CONSTRAINT pk_sla_policy_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_sla_policy_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);
