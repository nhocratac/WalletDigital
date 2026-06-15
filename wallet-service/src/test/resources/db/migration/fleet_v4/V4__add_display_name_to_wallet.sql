-- SP5 Task 6 fleet-migration test fixture: an EXPAND-phase migration (ADD a nullable column only,
-- backward-compatible — see design §6.3). Fleet migrate runs this on top of the V1..V3 baseline.
-- A tenant that already has a `display_name` column will FAIL here (duplicate column), exercising
-- per-tenant failure isolation (that tenant → MIGRATION_FAILED, others still advance).
ALTER TABLE wallet ADD COLUMN display_name VARCHAR(255);
