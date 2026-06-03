-- Reverse of V4__organizations.sql (CON-12). Flyway Community does not run undo scripts, so this
-- lives outside db/migration and Flyway never executes it automatically. It is the documented,
-- tested manual reverse:
--
--   psql "$DATABASE_URL" -f src/main/resources/db/undo/U4__organizations.sql
--   DELETE FROM flyway_schema_history WHERE version = '4';
--
-- Locally, `docker compose down -v` is the simplest full reset.
--
-- DROP ORDER IS LOAD-BEARING (the mirror of V4): the three FKs reference `organizations`, so a bare
-- DROP TABLE would fail while they exist (the only way through would be CASCADE, which silently rips
-- the constraints out — exactly what we avoid). Drop the FKs FIRST, then the table.

ALTER TABLE sources  DROP CONSTRAINT IF EXISTS fk_sources_org_id;
ALTER TABLE events   DROP CONSTRAINT IF EXISTS fk_events_org_id;
ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS fk_api_keys_org_id;

DROP TABLE IF EXISTS organizations;
