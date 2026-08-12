-- V1: Create Hibernate Envers revision tracking table
--
-- This table is used by Hibernate Envers to record a revision number and
-- timestamp for every audited change (INSERT, UPDATE, DELETE).
--
-- The sequence uses allocationSize=50 to match the JPA generator configuration
-- in RevisionInfo.java, allowing Hibernate to allocate IDs in batches for
-- performance without exhausting sequence values.

CREATE SEQUENCE IF NOT EXISTS revinfo_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS revinfo (
    rev      INTEGER NOT NULL DEFAULT nextval('revinfo_seq'),
    revtstmp BIGINT,
    CONSTRAINT pk_revinfo PRIMARY KEY (rev)
);
