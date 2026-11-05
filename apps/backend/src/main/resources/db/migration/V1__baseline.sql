-- What: Baseline migration. It creates no tables.
-- Why: It proves Flyway runs against PostgreSQL before domain tables exist.
-- How to use: Add new changes as V2 and later. Never edit a migration after it is merged.
-- Data: It changes and deletes no data.
-- Testing: ApplicationIntegrationTest checks that it was applied.
SELECT 1;
