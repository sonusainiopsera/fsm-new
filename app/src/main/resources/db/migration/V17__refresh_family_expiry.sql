-- =============================================================================
-- V17: Add absolute_expires_at to refresh_token_family.
-- =============================================================================
-- Family lifetime is fixed at creation time and must never be extended during
-- rotation. Storing it on the family row lets the poller validate expiry using
-- database time without involving the application clock (clock-skew safety).
-- =============================================================================

ALTER TABLE refresh_token_family
    ADD COLUMN absolute_expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '7 days');

COMMENT ON COLUMN refresh_token_family.absolute_expires_at
    IS 'Hard ceiling for the family: rotation is refused after this instant regardless of token consumed_at state. Set at login, never updated.';
