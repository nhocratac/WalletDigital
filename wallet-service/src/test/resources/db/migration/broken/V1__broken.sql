-- SP5 Task 5 test fixture: intentionally invalid SQL so flyway.migrate() fails, exercising the
-- provisioning failure path (status → MIGRATION_FAILED, never half-ACTIVE).
THIS IS NOT VALID SQL AND MUST FAIL;
