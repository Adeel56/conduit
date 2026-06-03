-- Reverse of V5__destinations_and_routes.sql (CON-10). Flyway Community does not run undo scripts,
-- so this lives outside db/migration and Flyway never executes it automatically. It is the
-- documented, tested manual reverse:
--
--   psql "$DATABASE_URL" -f src/main/resources/db/undo/U5__destinations_and_routes.sql
--   DELETE FROM flyway_schema_history WHERE version = '5';
--
-- Locally, `docker compose down -v` is the simplest full reset (drops the volume entirely).
--
-- DROP ORDER IS LOAD-BEARING (the mirror of V4/V5): `routes` has FKs to `organizations`, `sources`,
-- and `destinations`; `destinations` has an FK to `organizations`. A bare DROP TABLE would fail
-- while those constraints exist (the only way through would be CASCADE, which silently rips the
-- constraints out — exactly what we avoid). Drop the FKs FIRST, then the tables, dependents first:
-- `routes` (which references `destinations`) before `destinations`.

ALTER TABLE routes       DROP CONSTRAINT IF EXISTS routes_org_id_fkey;
ALTER TABLE routes       DROP CONSTRAINT IF EXISTS routes_source_id_fkey;
ALTER TABLE routes       DROP CONSTRAINT IF EXISTS routes_destination_id_fkey;
ALTER TABLE destinations DROP CONSTRAINT IF EXISTS destinations_org_id_fkey;

DROP TABLE IF EXISTS routes;
DROP TABLE IF EXISTS destinations;
