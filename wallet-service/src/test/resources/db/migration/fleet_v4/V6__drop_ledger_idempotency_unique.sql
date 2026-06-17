-- SP7 Bước 1 Task 5 fleet-migration test fixture: PLACEHOLDER version-aligned cho tenant V6
-- (tenant V6 THẬT là Java migration ở db.migration.tenant — backfill + DROP UNIQUE idempotency_key).
-- Trong fixture, tenant được provision qua location THẬT nên tenant V6 (Java) ĐÃ chạy + đã bỏ index;
-- file này CHỈ cần khai báo version 6 để fleet_v4 có ladder liền mạch V1..V6 trước khi ship V7
-- (display_name). KHÔNG lặp lại DROP INDEX (sẽ lỗi 1091 vì index đã bị tenant V6 bỏ) — để no-op.
SELECT 1;
