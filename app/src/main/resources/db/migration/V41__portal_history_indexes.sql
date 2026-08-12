-- V41: Additive composite indexes supporting the portal service history query path.
--
-- AC-5 / WO-172: Filtering and sorting are applied before pagination in SQL.
--
-- Index 1: supports the most common portal history query:
--   WHERE wo.site_id = ? (or site.customer_id = ?) ORDER BY wo.created_at DESC, wo.id ASC
--   LIMIT 50 OFFSET ?
--
-- Index 2: supports the scoping join path: site → customer.
--   Used when the database chooses to join site first and filter by customer_id.
--
-- Both indexes are additive (CREATE INDEX IF NOT EXISTS) to remain safe on re-run.

CREATE INDEX IF NOT EXISTS idx_wo_site_created_id
    ON work_order (site_id, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_site_customer_id
    ON site (customer_id, id);
