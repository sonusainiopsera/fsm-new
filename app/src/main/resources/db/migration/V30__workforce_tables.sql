-- =============================================================================
-- V30: Workforce module — technician profiles, skills, availability, position
-- =============================================================================
-- Extends existing technician table with workforce columns.
-- Creates skill, technician_skill, technician_availability_window,
-- technician_absence and technician_position tables.
-- Creates Envers AUD tables wired to REVINFO.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Extend existing technician table with workforce-profile columns
-- ---------------------------------------------------------------------------
ALTER TABLE technician
    ADD COLUMN display_name       VARCHAR(255),
    ADD COLUMN mobile_phone       TEXT,          -- AES-256-GCM encrypted (CONFIDENTIAL)
    ADD COLUMN timezone           VARCHAR(50) NOT NULL DEFAULT 'UTC',
    ADD COLUMN home_base_site_id  UUID REFERENCES site(id);

-- ---------------------------------------------------------------------------
-- skill: admin-managed skill reference catalogue
-- ---------------------------------------------------------------------------
CREATE TABLE skill (
    id           UUID         NOT NULL PRIMARY KEY,
    code         VARCHAR(50)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_skill_code UNIQUE (code)
);

-- ---------------------------------------------------------------------------
-- technician_skill: proficiency link between technician and skill
-- ---------------------------------------------------------------------------
CREATE TABLE technician_skill (
    id               UUID        NOT NULL PRIMARY KEY,
    technician_id    UUID        NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
    skill_id         UUID        NOT NULL REFERENCES skill(id),
    proficiency      VARCHAR(20) NOT NULL,
    years_experience INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT chk_ts_proficiency  CHECK (proficiency IN ('NOVICE','COMPETENT','PROFICIENT','EXPERT')),
    CONSTRAINT chk_ts_years_exp    CHECK (years_experience IS NULL OR years_experience >= 0),
    CONSTRAINT uq_technician_skill UNIQUE (technician_id, skill_id)
);

CREATE INDEX idx_ts_technician_id ON technician_skill(technician_id);
CREATE INDEX idx_ts_skill_id      ON technician_skill(skill_id);

-- ---------------------------------------------------------------------------
-- technician_availability_window: recurring weekly working windows (ISO day 1=Mon..7=Sun)
-- ---------------------------------------------------------------------------
CREATE TABLE technician_availability_window (
    id             UUID        NOT NULL PRIMARY KEY,
    technician_id  UUID        NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
    day_of_week    SMALLINT    NOT NULL,
    start_time     TIME        NOT NULL,
    end_time       TIME        NOT NULL,
    effective_from DATE        NOT NULL,
    effective_to   DATE,                         -- NULL = open-ended

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT chk_taw_day_of_week    CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_taw_end_after_start CHECK (end_time > start_time),
    CONSTRAINT chk_taw_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_taw_technician_id ON technician_availability_window(technician_id);
CREATE INDEX idx_taw_tech_day      ON technician_availability_window(technician_id, day_of_week);

-- ---------------------------------------------------------------------------
-- technician_absence: dated absence records with controlled reason vocabulary
-- ---------------------------------------------------------------------------
CREATE TABLE technician_absence (
    id            UUID        NOT NULL PRIMARY KEY,
    technician_id UUID        NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
    starts_at     TIMESTAMPTZ NOT NULL,
    ends_at       TIMESTAMPTZ NOT NULL,
    reason        VARCHAR(30) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT chk_ta_reason            CHECK (reason IN ('ANNUAL_LEAVE','SICK_LEAVE','TRAINING','PUBLIC_HOLIDAY','OTHER')),
    CONSTRAINT chk_ta_ends_after_starts CHECK (ends_at > starts_at)
);

CREATE INDEX idx_ta_technician_id ON technician_absence(technician_id);
CREATE INDEX idx_ta_tech_starts   ON technician_absence(technician_id, starts_at DESC);

-- ---------------------------------------------------------------------------
-- technician_position: last-known location; lat/lng AES-256-GCM encrypted
-- Only one position row per technician; overwritten via last-write-wins logic at service layer.
-- ---------------------------------------------------------------------------
CREATE TABLE technician_position (
    id            UUID        NOT NULL PRIMARY KEY,
    technician_id UUID        NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
    latitude      TEXT        NOT NULL,          -- AES-256-GCM encrypted (CONFIDENTIAL)
    longitude     TEXT        NOT NULL,          -- AES-256-GCM encrypted (CONFIDENTIAL)
    captured_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_technician_position UNIQUE (technician_id)
);

CREATE INDEX idx_tp_technician_captured ON technician_position(technician_id, captured_at DESC);

-- =============================================================================
-- Envers AUD tables — mirrors of entity columns; version excluded per
-- do_not_audit_optimistic_locking_field=true; wired to revinfo via FK.
-- =============================================================================

CREATE TABLE technician_aud (
    id                UUID        NOT NULL,
    rev               INTEGER     NOT NULL,
    revtype           SMALLINT    NOT NULL,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    user_id           UUID,
    employee_no       VARCHAR(50),
    display_name      VARCHAR(255),
    mobile_phone      TEXT,
    timezone          VARCHAR(50),
    home_base_site_id UUID,
    is_active         BOOLEAN,
    CONSTRAINT pk_technician_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_technician_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
CREATE INDEX idx_technician_aud_rev ON technician_aud(rev);

CREATE TABLE skill_aud (
    id           UUID        NOT NULL,
    rev          INTEGER     NOT NULL,
    revtype      SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    code         VARCHAR(50),
    display_name VARCHAR(255),
    is_active    BOOLEAN,
    CONSTRAINT pk_skill_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_skill_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE TABLE technician_skill_aud (
    id               UUID        NOT NULL,
    rev              INTEGER     NOT NULL,
    revtype          SMALLINT    NOT NULL,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    technician_id    UUID,
    skill_id         UUID,
    proficiency      VARCHAR(20),
    years_experience INTEGER,
    CONSTRAINT pk_ts_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_ts_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE TABLE technician_availability_window_aud (
    id             UUID        NOT NULL,
    rev            INTEGER     NOT NULL,
    revtype        SMALLINT    NOT NULL,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    technician_id  UUID,
    day_of_week    SMALLINT,
    start_time     TIME,
    end_time       TIME,
    effective_from DATE,
    effective_to   DATE,
    CONSTRAINT pk_taw_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_taw_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE TABLE technician_absence_aud (
    id            UUID        NOT NULL,
    rev           INTEGER     NOT NULL,
    revtype       SMALLINT    NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    technician_id UUID,
    starts_at     TIMESTAMPTZ,
    ends_at       TIMESTAMPTZ,
    reason        VARCHAR(30),
    CONSTRAINT pk_ta_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_ta_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
