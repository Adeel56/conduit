-- Reverse of V3__api_keys.sql (CON-8). Flyway Community does not run undo scripts, so this lives
-- outside db/migration and Flyway never executes it automatically. It is the documented, tested
-- manual reverse:
--
--   psql "$DATABASE_URL" -f src/main/resources/db/undo/U3__api_keys.sql
--   DELETE FROM flyway_schema_history WHERE version = '3';
--
-- Locally, `docker compose down -v` is the simplest full reset.
--
-- api_keys has no dependents, so a single drop suffices.

DROP TABLE IF EXISTS api_keys;
