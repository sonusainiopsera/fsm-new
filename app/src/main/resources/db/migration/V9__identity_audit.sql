-- V9__identity_audit.sql
-- Extends app_user_aud with the new identity columns and adds role_assignment_aud.
-- All Envers audit tables are declared here; none are generated at runtime
-- (spring.jpa.hibernate.ddl-auto is 'none' / 'validate' in every profile).

-- ==============================================================
-- Extend app_user_aud with new identity columns
-- Mirroring V8 additions; full_name column stays for historical revisions.
-- ==============================================================
ALTER TABLE app_user_aud ADD COLUMN display_name     VARCHAR(255);
ALTER TABLE app_user_aud ADD COLUMN external_subject VARCHAR(255);
ALTER TABLE app_user_aud ADD COLUMN updated_at       TIMESTAMPTZ;

-- ==============================================================
-- role_assignment_aud
-- Mirrors role_assignment; audit columns are nullable to support DEL revisions.
-- Wired to the existing REVINFO table and revinfo_seq from EPIC-01 (V5).
-- ==============================================================
CREATE TABLE role_assignment_aud (
    id         UUID        NOT NULL,
    REV        INTEGER     NOT NULL,
    REVTYPE    SMALLINT,
    user_id    UUID,
    role_name  VARCHAR(50),
    granted_at TIMESTAMPTZ,
    granted_by VARCHAR(255),
    CONSTRAINT pk_role_assignment_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_role_assignment_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);
