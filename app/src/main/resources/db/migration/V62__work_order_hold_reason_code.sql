-- WO-152: Record the parts-unavailability reason code on work_order for BR-14 breach coding
-- and the parts-caused repeat-visit metric. Expand-only: nullable column, no NOT NULL constraint.

ALTER TABLE work_order     ADD COLUMN hold_reason_code VARCHAR(50);
ALTER TABLE work_order_aud ADD COLUMN hold_reason_code VARCHAR(50);
