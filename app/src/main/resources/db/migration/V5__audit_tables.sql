-- V5__audit_tables.sql
-- Hibernate Envers audit infrastructure.
-- All objects are created here (not by Hibernate auto-ddl) so that ddl-auto: validate succeeds.
-- Audit tables use composite (id, REV) primary keys; all audited columns are nullable to
-- accommodate partial state captured at deletion.

-- ============================================================
-- Revision sequence (INCREMENT BY 1 matches allocationSize=1 in AppRevision).
-- ============================================================
CREATE SEQUENCE revinfo_seq START WITH 1 INCREMENT BY 1;

-- ============================================================
-- REVINFO — master revision registry
-- REV       : revision number (PK, from revinfo_seq)
-- REVTSTMP  : epoch millis at which the revision was created
-- actor_user_id : authenticated user subject, or "SYSTEM" for background jobs
-- actor_role    : primary role of the authenticated user (stripped of ROLE_ prefix)
-- trace_id      : request traceId from MDC (UUID)
-- client_ip     : originating IP from MDC (IPv4 or IPv6)
-- ============================================================
CREATE TABLE REVINFO (
    REV           INTEGER      NOT NULL,
    REVTSTMP      BIGINT       NOT NULL,
    actor_user_id VARCHAR(255),
    actor_role    VARCHAR(50),
    trace_id      VARCHAR(36),
    client_ip     VARCHAR(45),
    CONSTRAINT pk_revinfo PRIMARY KEY (REV)
);

-- ============================================================
-- work_order_aud
-- Mirrors work_order columns; site_id stored as FK value (not joined).
-- BRIN index: append-only table ordered by insertion → far cheaper than B-tree.
-- ============================================================
CREATE TABLE work_order_aud (
    id                     UUID        NOT NULL,
    REV                    INTEGER     NOT NULL,
    REVTYPE                SMALLINT,
    reference              VARCHAR(50),
    state                  VARCHAR(50),
    priority               VARCHAR(20),
    site_id                UUID,
    assigned_technician_id UUID,
    description            VARCHAR(4000),
    created_at             TIMESTAMPTZ,
    version                INTEGER,
    CONSTRAINT pk_work_order_aud   PRIMARY KEY (id, REV),
    CONSTRAINT fk_work_order_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX brin_work_order_aud_rev ON work_order_aud USING BRIN (REV);

-- ============================================================
-- assignment_aud
-- ============================================================
CREATE TABLE assignment_aud (
    id            UUID        NOT NULL,
    REV           INTEGER     NOT NULL,
    REVTYPE       SMALLINT,
    work_order_id UUID,
    technician_id UUID,
    assigned_at   TIMESTAMPTZ,
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ,
    version       INTEGER,
    CONSTRAINT pk_assignment_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_assignment_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- technician_certification_aud
-- No version column in the source table.
-- ============================================================
CREATE TABLE technician_certification_aud (
    id                 UUID        NOT NULL,
    REV                INTEGER     NOT NULL,
    REVTYPE            SMALLINT,
    technician_id      UUID,
    certification_code VARCHAR(50),
    issued_at          TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    CONSTRAINT pk_tech_cert_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_tech_cert_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- app_user_aud
-- password_hash is intentionally absent — @NotAudited in AppUser entity.
-- ============================================================
CREATE TABLE app_user_aud (
    id         UUID        NOT NULL,
    REV        INTEGER     NOT NULL,
    REVTYPE    SMALLINT,
    email      VARCHAR(255),
    full_name  VARCHAR(255),
    active     BOOLEAN,
    created_at TIMESTAMPTZ,
    version    INTEGER,
    CONSTRAINT pk_app_user_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_app_user_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- site_aud
-- Mirrors only JPA-mapped columns (address fields are unmapped stubs in the entity).
-- ============================================================
CREATE TABLE site_aud (
    id          UUID        NOT NULL,
    REV         INTEGER     NOT NULL,
    REVTYPE     SMALLINT,
    name        VARCHAR(255),
    customer_id UUID,
    created_at  TIMESTAMPTZ,
    version     INTEGER,
    CONSTRAINT pk_site_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_site_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- sla_policy_aud
-- No version column in the source entity.
-- ============================================================
CREATE TABLE sla_policy_aud (
    id                 UUID           NOT NULL,
    REV                INTEGER        NOT NULL,
    REVTYPE            SMALLINT,
    priority           VARCHAR(20),
    response_minutes   INTEGER,
    resolution_minutes INTEGER,
    at_risk_fraction   NUMERIC(3, 2),
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    CONSTRAINT pk_sla_policy_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_sla_policy_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);
