-- Cleanup: delete in reverse FK order so constraints are never violated.
DELETE FROM stock_ledger;
DELETE FROM stock_balance;
DELETE FROM assignment;
DELETE FROM work_order;
DELETE FROM technician_certification;
DELETE FROM asset;
DELETE FROM stock_location;
DELETE FROM site;
DELETE FROM technician;
DELETE FROM user_role;
DELETE FROM app_user;
DELETE FROM customer;
DELETE FROM part;
-- sla_policy rows come from V2 migration; leave them (tests don't write them)
