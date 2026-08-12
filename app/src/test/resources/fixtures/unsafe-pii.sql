-- DELIBERATELY UNSAFE FIXTURE — DO NOT COPY, DO NOT USE IN PRODUCTION
--
-- This file is used ONLY by FixturePiiScannerTest to prove the CI scanner
-- detects personal-data patterns and fails the build.
-- It intentionally contains a synthetic (fake) email and phone number.
--
-- allow-list-exempt: false  ← no allow-list entry exists for this file intentionally

INSERT INTO app_user (id, email, full_name, phone)
VALUES (
    'aaaaaaaa-0000-0000-0000-000000000001',
    'test.user@example-unsafe.com',         -- UNSAFE: real-looking email pattern
    'Test User',
    '+44 7700 900123'                        -- UNSAFE: real-looking phone pattern
);
