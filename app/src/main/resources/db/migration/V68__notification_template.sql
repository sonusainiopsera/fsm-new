-- =============================================================================
-- V68: Notification template store and dead-letter table (WO-196)
-- =============================================================================
-- Expand-only: no columns dropped, no tables altered destructively.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- notification_template: versioned, channel-aware message templates
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_template (
    id               UUID            NOT NULL,
    template_key     TEXT            NOT NULL,
    channel          VARCHAR(10)     NOT NULL CHECK (channel IN ('EMAIL','SMS','PUSH','IN_APP')),
    locale           VARCHAR(10)     NOT NULL DEFAULT 'en',
    version          INTEGER         NOT NULL DEFAULT 1,
    active           BOOLEAN         NOT NULL DEFAULT true,
    subject_template TEXT,                            -- null for channels that have no subject
    body_template    TEXT            NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_template PRIMARY KEY (id)
);

-- One row uniquely identifies a template snapshot
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_template_version
    ON notification_template (template_key, channel, locale, version);

-- Enforce exactly one active version per key/channel/locale
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_template_active
    ON notification_template (template_key, channel, locale)
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_notification_template_key_channel
    ON notification_template (template_key, channel, locale);

-- ---------------------------------------------------------------------------
-- notification_dead_letter: quarantine for permanently-failed fan-out events
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_dead_letter (
    id             UUID         NOT NULL,
    event_id       UUID         NOT NULL,
    consumer       TEXT         NOT NULL,
    failure_reason TEXT         NOT NULL,
    payload_hash   TEXT         NOT NULL,
    attempt_count  INTEGER      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_dead_letter PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_notification_dead_letter_event_id
    ON notification_dead_letter (event_id);

CREATE INDEX IF NOT EXISTS idx_notification_dead_letter_created_at
    ON notification_dead_letter (created_at DESC);

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON notification_template TO fieldservice_app;
GRANT SELECT, INSERT ON notification_dead_letter     TO fieldservice_app;

-- ---------------------------------------------------------------------------
-- Seed templates — all eight trigger categories on IN_APP channel
-- ---------------------------------------------------------------------------
-- Template placeholder syntax: {{paramName}}
-- No expression evaluation — strict parameter substitution only.

-- 1. Work order assigned
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.assigned',
    'IN_APP', 'en', 1, true,
    'Work order assigned to you',
    'Work order {{workOrderReference}} ({{priority}} priority) has been assigned to you.'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.assigned',
    'EMAIL', 'en', 1, true,
    'Work order {{workOrderReference}} assigned to you',
    'Dear {{technicianName}}, work order {{workOrderReference}} ({{priority}} priority) has been assigned to you. Please review the details in the field service portal.'
);

-- 2. Work order reassigned (outgoing technician)
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.reassigned',
    'IN_APP', 'en', 1, true,
    'Work order reassigned',
    'Work order {{workOrderReference}} has been reassigned. Reason: {{reassignmentReason}}.'
);

-- 3. SLA at-risk
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'sla.risk.flagged',
    'IN_APP', 'en', 1, true,
    'SLA at-risk — {{minutesRemaining}} minutes remaining',
    'Work order {{workOrderId}} is at risk. {{minutesRemaining}} minutes remaining. Projection basis: {{projectionBasis}}.'
);

-- 4. SLA breach
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'sla.breached',
    'IN_APP', 'en', 1, true,
    'SLA breached — {{overrunMinutes}} minutes overrun',
    'Work order {{workOrderId}} has breached its SLA deadline by {{overrunMinutes}} minutes.'
);

-- 5. Customer-visible status change
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.status.customer',
    'IN_APP', 'en', 1, true,
    'Service request update',
    'Your service request {{workOrderReference}} status is now: {{statusLabel}}.'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.status.customer',
    'EMAIL', 'en', 1, true,
    'Update on your service request {{workOrderReference}}',
    'Dear {{customerName}}, your service request {{workOrderReference}} has been updated. Current status: {{statusLabel}}. Visit our portal for more details.'
);

-- 6. Certification expiring (IN_APP — managed by CertificationAlertConsumer)
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'certification.expiring',
    'IN_APP', 'en', 1, true,
    'Certification expiring — {{certificationTypeCode}}',
    'Certification {{certificationTypeCode}} expires on {{expiresOn}} ({{daysToExpiry}} days remaining).'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'certification.expired',
    'IN_APP', 'en', 1, true,
    'Certification EXPIRED — {{certificationTypeCode}}',
    'Certification {{certificationTypeCode}} expired on {{expiresOn}} ({{daysExpired}} days ago). Immediate action required.'
);

-- 7. Confirmed appointment changed
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'appointment.changed',
    'IN_APP', 'en', 1, true,
    'Appointment change on your service request',
    'The confirmed appointment for your service request {{workOrderReference}} has changed. Please check the portal for updated details.'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'appointment.changed',
    'EMAIL', 'en', 1, true,
    'Your appointment for {{workOrderReference}} has changed',
    'Dear {{customerName}}, the confirmed appointment window for service request {{workOrderReference}} has been updated. Please log in to the service portal to review the changes.'
);

-- 8a. Request rejected
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.rejected',
    'IN_APP', 'en', 1, true,
    'Service request cannot be progressed',
    'Your service request {{workOrderReference}} has been cancelled and cannot be progressed.'
);

-- 8b. Duplicate-linked
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.duplicate.linked',
    'IN_APP', 'en', 1, true,
    'Your service request has been linked to an existing case',
    'Your service request {{workOrderReference}} has been linked to an existing open case. You will receive updates through that case.'
);

-- 8c. Closure CSAT survey
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.closed.survey',
    'IN_APP', 'en', 1, true,
    'How did we do? Tell us about your experience',
    'Your service request {{workOrderReference}} has been completed. We would appreciate your feedback — it only takes a minute.'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    gen_random_uuid(),
    'workorder.closed.survey',
    'EMAIL', 'en', 1, true,
    'Please share your feedback on service request {{workOrderReference}}',
    'Dear {{customerName}}, your service request {{workOrderReference}} has been completed. Please take a moment to share your experience at your service portal.'
);
