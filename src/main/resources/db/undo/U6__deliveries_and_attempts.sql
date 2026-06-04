-- Reverse of V6__deliveries_and_attempts.sql (CON-13). Flyway Community does not run undo scripts, so
-- this lives outside db/migration and is never executed automatically. The documented, tested reverse:
--
--   psql "$DATABASE_URL" -f src/main/resources/db/undo/U6__deliveries_and_attempts.sql
--   DELETE FROM flyway_schema_history WHERE version = '6';
--
-- Locally, `docker compose down -v` is the simplest full reset.
--
-- DROP ORDER: dependents first. `attempts` references `deliveries`, so it goes first; dropping a table
-- removes its own outgoing FK constraints, and nothing else references these two (they are leaf tables),
-- so plain drops in this order suffice (no separate ALTER ... DROP CONSTRAINT needed).

DROP TABLE IF EXISTS attempts;
DROP TABLE IF EXISTS deliveries;
