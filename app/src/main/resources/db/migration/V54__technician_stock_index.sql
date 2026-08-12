-- V54: Add composite index for the technician vehicle stock endpoint query.
-- GET /api/v1/technicians/me/stock joins stock_balance → stock_location → part
-- on technician_id + location_type = 'VEHICLE'; this index makes that lookup O(log n).
-- Additive only — no destructive change to existing rows.

CREATE INDEX IF NOT EXISTS idx_stock_location_technician_vehicle
    ON stock_location (technician_id, location_type)
    WHERE technician_id IS NOT NULL AND location_type = 'VEHICLE';
