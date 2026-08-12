-- V36: Blind-index columns for equality lookup on encrypted PII fields
-- Adds HMAC-SHA-256 blind-index columns alongside encrypted PII columns so that
-- equality lookups (e.g. find invitation by contact email) continue to work
-- without decrypting the whole table.
--
-- LIMITATIONS (documented as required by AC-5):
--   • Equality lookup ONLY — range, prefix and LIKE queries are unsupported.
--   • HMAC leaks equality by design; any adversary with the index column can confirm
--     whether two records share the same plaintext value.  This is an accepted
--     trade-off; the index key must be distinct from the data-encryption key.
--   • Column is nullable: rows written before this migration (or during the expand
--     backfill window) will have NULL in the blind-index column until backfilled.
--
-- Affected tables (expand phase only; plaintext columns dropped in a later release):
--   portal_invitation         — contact_email_idx (for portal identity resolution)
--   portal_invitation_aud     — contact_email_idx (Envers audit mirror)

-- portal_invitation: blind index on contact_email for portal identity lookup
ALTER TABLE portal_invitation
    ADD COLUMN IF NOT EXISTS contact_email_idx VARCHAR(64);

-- Btree index for equality lookup via blind index
CREATE INDEX IF NOT EXISTS idx_portal_inv_email_idx
    ON portal_invitation (contact_email_idx)
    WHERE contact_email_idx IS NOT NULL;

-- Envers audit table must carry the same column so Hibernate Envers does not fail
-- at runtime (it expects audit table schema to mirror the entity schema).
ALTER TABLE portal_invitation_aud
    ADD COLUMN IF NOT EXISTS contact_email_idx VARCHAR(64);
