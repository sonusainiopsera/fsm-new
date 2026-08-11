-- V3__indexes.sql
-- Indexes required by P0 read paths.

-- work_order: stable keyset pagination by created_at DESC + id
CREATE INDEX idx_wo_created_at_id ON work_order (created_at DESC, id);

-- work_order: state filter (dispatcher work queue)
CREATE INDEX idx_wo_state ON work_order (state);

-- work_order: technician scope predicate
CREATE INDEX idx_wo_assigned_tech ON work_order (assigned_technician_id);

-- work_order: site scope predicate (CUSTOMER role join target)
CREATE INDEX idx_wo_site ON work_order (site_id);

-- site: customer scope predicate
CREATE INDEX idx_site_customer ON site (customer_id);

-- technician_certification: eligibility gate query (technician + expiry)
CREATE INDEX idx_tech_cert_tech_expiry ON technician_certification (technician_id, expires_at);

-- stock_ledger: ledger audit queries by part and time
CREATE INDEX idx_stock_ledger_part_created ON stock_ledger (part_id, created_at);

-- assignment: lookup by work order
CREATE INDEX idx_assignment_wo ON assignment (work_order_id);
