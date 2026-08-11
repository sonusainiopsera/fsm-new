-- Cleanup script run after integration test classes to reset test database state.
DELETE FROM work_order;
DELETE FROM site;
DELETE FROM customer_account;
