-- V42: Composite indexes for the portal service history endpoint (WO-172).
--
-- The portal history query joins work_order → site and filters / sorts by:
--   (1) site_id (optional filter param)
--   (2) created_at DESC + id ASC (default sort + mandatory tie-break)
--
-- idx_wo_site_created_id supports the (site_id, created_at, id) access path used when
-- a siteId filter is supplied: the planner can use the composite index to satisfy both
-- the predicate and the ORDER BY without a sort step.
--
-- idx_site_customer_id already exists in V1 (site(customer_id)).
-- idx_work_order_created_at_id already exists in V1 (work_order(created_at DESC, id)).
-- Both pre-existing indexes support the unfiltered history query.

CREATE INDEX IF NOT EXISTS idx_wo_site_created_id
    ON work_order(site_id, created_at DESC, id);
