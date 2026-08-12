-- V63__notification_template.sql
-- Versioned template store and dead-letter quarantine for the notification fan-out layer.
-- Expand-only: no destructive changes to existing tables.

-- ── Versioned notification templates ──────────────────────────────────────────────────────────
CREATE TABLE notification_template (
    id               UUID         NOT NULL,
    template_key     TEXT         NOT NULL,
    channel          VARCHAR(20)  NOT NULL,
    locale           VARCHAR(10)  NOT NULL DEFAULT 'en',
    version          INT          NOT NULL DEFAULT 1,
    active           BOOLEAN      NOT NULL DEFAULT FALSE,
    subject_template TEXT,
    body_template    TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_template PRIMARY KEY (id),
    CONSTRAINT chk_nt_channel CHECK (channel IN ('EMAIL','SMS','PUSH','IN_APP')),
    CONSTRAINT uq_nt_key_channel_locale_version UNIQUE (template_key, channel, locale, version)
);

-- Exactly one active version per (template_key, channel, locale)
CREATE UNIQUE INDEX uq_nt_active_version
    ON notification_template (template_key, channel, locale)
    WHERE active = TRUE;

CREATE INDEX idx_nt_key_channel ON notification_template (template_key, channel);

-- ── Dead-letter quarantine ────────────────────────────────────────────────────────────────────
CREATE TABLE notification_dead_letter (
    id              UUID         NOT NULL,
    event_id        UUID         NOT NULL,
    consumer        TEXT         NOT NULL,
    failure_reason  TEXT         NOT NULL,
    payload_hash    TEXT         NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_dead_letter PRIMARY KEY (id)
);

CREATE INDEX idx_ndl_created_at ON notification_dead_letter (created_at DESC);
CREATE INDEX idx_ndl_event_id   ON notification_dead_letter (event_id);

-- ── Seed: IN_APP templates for all 8 trigger categories ───────────────────────────────────────

-- 1. Work order assigned
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000001',
    'assignment_notification', 'IN_APP', 'en', 1, TRUE,
    'Work order assigned to you',
    'You have been assigned to work order {workOrderRef}. Please review the details and confirm your availability.'
);

-- 2. Work order reassigned (revoked)
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000002',
    'reassignment_notification', 'IN_APP', 'en', 1, TRUE,
    'Work order reassignment',
    'Work order {workOrderRef} has been reassigned. The previous assignment has been cancelled.'
);

-- 3. SLA at-risk
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000003',
    'sla_at_risk', 'IN_APP', 'en', 1, TRUE,
    'SLA at risk — {workOrderRef}',
    'Work order {workOrderRef} is at risk of breaching its SLA. Projection basis: {projectionBasis}. Please take action.'
);

-- 4. SLA breach
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000004',
    'sla_breach', 'IN_APP', 'en', 1, TRUE,
    'SLA breach recorded — {workOrderRef}',
    'Work order {workOrderRef} has breached its SLA. Overrun: {overrunMinutes} minutes. Reason: {breachReasonCode}.'
);

-- 5. Customer-visible status change
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000005',
    'customer_status_change', 'IN_APP', 'en', 1, TRUE,
    'Update on your service request',
    '{statusLabel}: {statusDescription}'
);

-- 6. Certification expiring
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000006',
    'certification_expiring', 'IN_APP', 'en', 1, TRUE,
    'Certification expiring soon',
    'A certification expires on {expiresOn} ({daysRemaining} day(s) remaining). Please arrange renewal to maintain eligibility.'
);

-- 7. Certification expired
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000007',
    'certification_expired', 'IN_APP', 'en', 1, TRUE,
    'Certification expired',
    'A certification expired on {expiresOn}. Please arrange renewal immediately to restore eligibility.'
);

-- 8. Appointment changed
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000008',
    'appointment_changed', 'IN_APP', 'en', 1, TRUE,
    'Your appointment has changed',
    'Your confirmed appointment for service request {workOrderRef} has been updated. New appointment: {appointmentDate}.'
);

-- 9. Request rejected
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000009',
    'request_rejected', 'IN_APP', 'en', 1, TRUE,
    'Service request update',
    'Your service request has been merged with an existing request. Reference: {survivingRef}. Reason: {reason}.'
);

-- 10. CSAT survey issued
INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000010',
    'csat_survey', 'IN_APP', 'en', 1, TRUE,
    'How did we do?',
    'Your service request {workOrderRef} has been completed. We would love to hear your feedback.'
);

-- ── EMAIL variants for operational triggers ───────────────────────────────────────────────────

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000011',
    'assignment_notification', 'EMAIL', 'en', 1, TRUE,
    'Work order assigned — {workOrderRef}',
    '<p>You have been assigned to work order <strong>{workOrderRef}</strong>.</p><p>Please review the details and confirm your availability.</p>'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000012',
    'sla_at_risk', 'EMAIL', 'en', 1, TRUE,
    'ACTION REQUIRED: SLA at risk — {workOrderRef}',
    '<p>Work order <strong>{workOrderRef}</strong> is at risk of breaching its SLA.</p><p>Projection basis: {projectionBasis}</p><p>Please take immediate action.</p>'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000013',
    'sla_breach', 'EMAIL', 'en', 1, TRUE,
    'SLA breach recorded — {workOrderRef}',
    '<p>Work order <strong>{workOrderRef}</strong> has breached its SLA.</p><p>Overrun: {overrunMinutes} minutes. Reason code: {breachReasonCode}.</p>'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000014',
    'customer_status_change', 'EMAIL', 'en', 1, TRUE,
    'Update on your service request — {workOrderRef}',
    '<p><strong>{statusLabel}</strong></p><p>{statusDescription}</p>'
);

INSERT INTO notification_template (id, template_key, channel, locale, version, active, subject_template, body_template)
VALUES (
    '00000000-0000-7300-8000-000000000015',
    'csat_survey', 'EMAIL', 'en', 1, TRUE,
    'How did we do? — {workOrderRef}',
    '<p>Your service request <strong>{workOrderRef}</strong> has been completed.</p><p>We would love to hear your feedback. Please rate your experience.</p>'
);
