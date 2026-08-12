-- V59: Add accuracy_metres and retain_until to technician_position (WO-159)

ALTER TABLE technician_position
    ADD COLUMN accuracy_metres INTEGER,
    ADD COLUMN retain_until    DATE;

-- Mirror columns in the Envers audit table
ALTER TABLE technician_position_aud
    ADD COLUMN accuracy_metres INTEGER,
    ADD COLUMN retain_until    DATE;
