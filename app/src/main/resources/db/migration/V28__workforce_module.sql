-- V28__workforce_module.sql
-- Workforce module: extends technician profile, skill registry, availability windows,
-- absences, and last-known position.  All new tables use UUIDv7 PKs (application-side).
-- Expand-only: no destructive changes to existing technician rows.
-- mobile_phone and position lat/long are TEXT columns to accommodate AES-256-GCM ciphertext.

-- ============================================================
-- Extend technician: workforce profile columns
-- ============================================================
ALTER TABLE technician
    ADD COLUMN IF NOT EXISTS employee_code     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS display_name      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mobile_phone      TEXT,
    ADD COLUMN IF NOT EXISTS timezone          VARCHAR(100) NOT NULL DEFAULT 'UTC',
    ADD COLUMN IF NOT EXISTS home_base_site_id UUID,
    ADD COLUMN IF NOT EXISTS active            BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ;

ALTER TABLE technician
    ADD CONSTRAINT fk_technician_home_site
        FOREIGN KEY (home_base_site_id) REFERENCES site (id)
        NOT VALID;

ALTER TABLE technician VALIDATE CONSTRAINT fk_technician_home_site;

-- Unique employee_code among active technicians only
CREATE UNIQUE INDEX uq_technician_employee_code_active
    ON technician (employee_code)
    WHERE active = TRUE AND employee_code IS NOT NULL;

-- ============================================================
-- technician_aud (Hibernate Envers) — all audited columns nullable for DEL revisions
-- ============================================================
CREATE TABLE IF NOT EXISTS technician_aud (
    id                 UUID        NOT NULL,
    REV                INTEGER     NOT NULL,
    REVTYPE            SMALLINT,
    user_id            UUID,
    full_name          VARCHAR(255),
    phone              VARCHAR(50),
    employee_code      VARCHAR(50),
    display_name       VARCHAR(255),
    mobile_phone       TEXT,
    timezone           VARCHAR(100),
    home_base_site_id  UUID,
    active             BOOLEAN,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    version            INTEGER,
    CONSTRAINT pk_technician_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_technician_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- skill — runtime-configurable reference data
-- ============================================================
CREATE TABLE skill (
    id           UUID         NOT NULL,
    code         VARCHAR(50)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version      INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_skill      PRIMARY KEY (id),
    CONSTRAINT uq_skill_code UNIQUE (code)
);

CREATE TABLE skill_aud (
    id           UUID        NOT NULL,
    REV          INTEGER     NOT NULL,
    REVTYPE      SMALLINT,
    code         VARCHAR(50),
    display_name VARCHAR(255),
    active       BOOLEAN,
    created_at   TIMESTAMPTZ,
    version      INTEGER,
    CONSTRAINT pk_skill_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_skill_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- technician_skill — links a technician to a skill with proficiency
-- ============================================================
CREATE TABLE technician_skill (
    id              UUID        NOT NULL,
    technician_id   UUID        NOT NULL,
    skill_id        UUID        NOT NULL,
    proficiency     VARCHAR(20) NOT NULL,
    years_experience INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_technician_skill       PRIMARY KEY (id),
    CONSTRAINT uq_technician_skill_pair  UNIQUE (technician_id, skill_id),
    CONSTRAINT fk_tech_skill_technician  FOREIGN KEY (technician_id) REFERENCES technician (id),
    CONSTRAINT fk_tech_skill_skill       FOREIGN KEY (skill_id)      REFERENCES skill (id),
    CONSTRAINT chk_tech_skill_proficiency CHECK (proficiency IN (
        'NOVICE', 'COMPETENT', 'PROFICIENT', 'EXPERT'
    )),
    CONSTRAINT chk_tech_skill_years CHECK (years_experience IS NULL OR years_experience >= 0)
);

CREATE TABLE technician_skill_aud (
    id               UUID        NOT NULL,
    REV              INTEGER     NOT NULL,
    REVTYPE          SMALLINT,
    technician_id    UUID,
    skill_id         UUID,
    proficiency      VARCHAR(20),
    years_experience INTEGER,
    created_at       TIMESTAMPTZ,
    version          INTEGER,
    CONSTRAINT pk_technician_skill_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_technician_skill_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- technician_availability_window — recurring weekly working windows
-- day_of_week: 1=Monday .. 7=Sunday (ISO-8601)
-- ============================================================
CREATE TABLE technician_availability_window (
    id             UUID      NOT NULL,
    technician_id  UUID      NOT NULL,
    day_of_week    SMALLINT  NOT NULL,
    start_time     TIME      NOT NULL,
    end_time       TIME      NOT NULL,
    effective_from DATE      NOT NULL,
    effective_to   DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version        INTEGER   NOT NULL DEFAULT 0,
    CONSTRAINT pk_tech_avail_window      PRIMARY KEY (id),
    CONSTRAINT fk_tech_avail_technician  FOREIGN KEY (technician_id) REFERENCES technician (id),
    CONSTRAINT chk_tech_avail_day        CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_tech_avail_times      CHECK (end_time > start_time),
    CONSTRAINT chk_tech_avail_effective  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_tech_avail_window_tech ON technician_availability_window (technician_id);

CREATE TABLE technician_availability_window_aud (
    id             UUID      NOT NULL,
    REV            INTEGER   NOT NULL,
    REVTYPE        SMALLINT,
    technician_id  UUID,
    day_of_week    SMALLINT,
    start_time     TIME,
    end_time       TIME,
    effective_from DATE,
    effective_to   DATE,
    created_at     TIMESTAMPTZ,
    version        INTEGER,
    CONSTRAINT pk_tech_avail_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_tech_avail_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- technician_absence — dated absence with reason
-- ============================================================
CREATE TABLE technician_absence (
    id            UUID        NOT NULL,
    technician_id UUID        NOT NULL,
    starts_at     TIMESTAMPTZ NOT NULL,
    ends_at       TIMESTAMPTZ NOT NULL,
    reason        VARCHAR(50) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version       INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_technician_absence      PRIMARY KEY (id),
    CONSTRAINT fk_tech_absence_technician FOREIGN KEY (technician_id) REFERENCES technician (id),
    CONSTRAINT chk_tech_absence_times     CHECK (ends_at > starts_at),
    CONSTRAINT chk_tech_absence_reason    CHECK (reason IN (
        'ANNUAL_LEAVE', 'SICK_LEAVE', 'TRAINING', 'PUBLIC_HOLIDAY', 'OTHER'
    ))
);

CREATE INDEX idx_tech_absence_tech ON technician_absence (technician_id);

CREATE TABLE technician_absence_aud (
    id            UUID        NOT NULL,
    REV           INTEGER     NOT NULL,
    REVTYPE       SMALLINT,
    technician_id UUID,
    starts_at     TIMESTAMPTZ,
    ends_at       TIMESTAMPTZ,
    reason        VARCHAR(50),
    created_at    TIMESTAMPTZ,
    version       INTEGER,
    CONSTRAINT pk_tech_absence_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_tech_absence_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- technician_position — last-known position (confidential, 90-day retention)
-- latitude and longitude stored as TEXT to accommodate AES-256-GCM ciphertext.
-- NOT audited — position history purged on retention schedule.
-- ============================================================
CREATE TABLE technician_position (
    id            UUID        NOT NULL,
    technician_id UUID        NOT NULL,
    latitude      TEXT        NOT NULL,
    longitude     TEXT        NOT NULL,
    captured_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_technician_position      PRIMARY KEY (id),
    CONSTRAINT fk_tech_position_technician FOREIGN KEY (technician_id) REFERENCES technician (id)
);

CREATE INDEX idx_tech_position_tech_time
    ON technician_position (technician_id, captured_at DESC);

-- ============================================================
-- Seed skill catalogue (8 skills)
-- UUID prefix: 00000000-0000-7028-8000-XXXXXXXXXXXX
-- ============================================================
INSERT INTO skill (id, code, display_name, active)
VALUES
    ('00000000-0000-7028-8000-000000000001', 'HVAC',        'HVAC Systems',              TRUE),
    ('00000000-0000-7028-8000-000000000002', 'ELECTRICAL',  'Electrical Systems',        TRUE),
    ('00000000-0000-7028-8000-000000000003', 'PLUMBING',    'Plumbing & Pipework',        TRUE),
    ('00000000-0000-7028-8000-000000000004', 'REFRIGERANT', 'Refrigerant Handling',      TRUE),
    ('00000000-0000-7028-8000-000000000005', 'MECHANICAL',  'Mechanical Engineering',    TRUE),
    ('00000000-0000-7028-8000-000000000006', 'BMS',         'Building Management Systems',TRUE),
    ('00000000-0000-7028-8000-000000000007', 'FIRE_SAFETY', 'Fire Safety Systems',       TRUE),
    ('00000000-0000-7028-8000-000000000008', 'ACCESS_CTRL', 'Access Control Systems',    FALSE)
ON CONFLICT (code) DO NOTHING;
