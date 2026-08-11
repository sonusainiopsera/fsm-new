-- =============================================================================
-- V114: Notification test fixtures (WO-195)
-- =============================================================================
-- Pre-populates notification delivery attempts and in-app notifications
-- for use in integration tests. User IDs match V100__test_fixtures.sql.
-- =============================================================================

-- Pre-existing SENT attempt (idempotency scenario)
INSERT INTO notification_delivery_attempt (id, event_id, channel, adapter, recipient_user_id, recipient_mask, attempt_no, outcome, provider_reference, duration_ms, failure_code, created_at)
VALUES (
    'bb000000-0000-7000-8000-000000000001',
    'ee000000-0000-0000-0000-000000000001',
    'EMAIL',
    'stub',
    'aaaaaaaa-0000-0000-0000-000000000001',  -- dispatcher user
    'd***@example.com',
    1,
    'SENT',
    'stub-ref-001',
    45,
    NULL,
    now() - INTERVAL '1 hour'
);

-- Pre-existing in-app notification (idempotency scenario)
INSERT INTO in_app_notification (id, recipient_user_id, event_id, category, title, body, severity, read_at, created_at)
VALUES (
    'cc000000-0000-7000-8000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000002',  -- manager user
    'ee000000-0000-0000-0000-000000000002',
    'WORK_ORDER',
    'Work order assigned',
    'You have been assigned to WO-001',
    'INFO',
    NULL,
    now() - INTERVAL '30 minutes'
);
