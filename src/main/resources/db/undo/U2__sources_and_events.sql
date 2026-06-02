-- Reverse of V2__sources_and_events.sql (CON-7). Flyway Community does not run undo scripts, so
-- this is NOT in db/migration and Flyway never executes it automatically. It is the documented,
-- tested manual reverse: run it against the target DB to roll the V2 schema back.
--
--   psql "$DATABASE_URL" -f src/main/resources/db/undo/U2__sources_and_events.sql
--   -- then remove V2's row from the history so a later `migrate` re-applies it:
--   DELETE FROM flyway_schema_history WHERE version = '2';
--
-- Locally, `docker compose down -v` is the simplest full reset (drops the volume entirely).
--
-- Drop order matters: events references sources, so events goes first.

DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS sources;
