-- =============================================================================
-- V3: Indexes for P0 read paths
-- =============================================================================

-- work_order: stable keyset pagination ordered by newest-first
CREATE INDEX idx_work_order_created_at_id ON work_order (created_at DESC, id);

-- work_order: filter by state (e.g. "show all OPEN work orders")
CREATE INDEX idx_work_order_state ON work_order (state);

-- work_order: scoped TECHNICIAN reads (WHERE assigned_technician_id = ?)
CREATE INDEX idx_work_order_assigned_technician ON work_order (assigned_technician_id);

-- technician_certification: certification eligibility gate (active + not-expired certs)
CREATE INDEX idx_tech_cert_technician_expires ON technician_certification (technician_id, expires_at);

-- site: scoped CUSTOMER reads via site → customer
CREATE INDEX idx_site_customer ON site (customer_id);

-- stock_ledger: audit / timeline queries (part history)
CREATE INDEX idx_stock_ledger_part_created ON stock_ledger (part_id, created_at);
