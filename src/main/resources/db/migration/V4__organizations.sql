-- V4 — organizations (CON-12): the real tenant entity behind org_id.
--
-- Until now `sources`, `events`, and `api_keys` carried an org_id that was a bare UUID pointing at
-- nothing (V2/V3 said so explicitly, deferring the FK to this ticket). This migration creates the
-- `organizations` table and turns org_id into a real foreign key, so a tenant row can no longer
-- reference a non-existent organization. Shape per docs/data-model.md / ADR-0008.
--
-- ORDER IS LOAD-BEARING. Adding the FK validates every existing row against `organizations`, so the
-- org rows must exist FIRST. Hence: create the table -> backfill one row per distinct existing
-- org_id -> only then add the constraints. On a fresh DB the backfill matches nothing (harmless);
-- on a populated deployment it is what makes the constraint satisfiable.
--
-- REVERSIBILITY: the tested reverse lives at src/main/resources/db/undo/U4__organizations.sql (kept
-- out of db/migration so Flyway never runs it). It drops the FKs BEFORE the table — see that file.

CREATE TABLE organizations (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    -- Human-friendly unique handle for the org (e.g. in URLs later). Unique + indexed.
    slug       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_organizations_slug ON organizations (slug);

-- Backfill: one organizations row per distinct org_id already present in the tenant tables, BEFORE
-- the FKs are added. UNION (not UNION ALL) de-dups, so an org referenced by several tables yields a
-- single row — deterministic and dup-safe. The synthesized name/slug are placeholders for orphan
-- ids that predate the org entity; a real org-management ticket can rename them. org_id::text is a
-- guaranteed-unique slug (org_id is already unique per organization).
INSERT INTO organizations (id, name, slug)
SELECT org_id,
       'Backfilled organization ' || org_id::text,
       org_id::text
FROM (
    SELECT org_id FROM sources
    UNION
    SELECT org_id FROM events
    UNION
    SELECT org_id FROM api_keys
) AS existing_orgs;

-- Now every org_id resolves to a real row; add the FK on each tenant table. No new index is needed
-- on the referencing columns: org_id is already indexed on all three (ix_sources_org_id,
-- ix_api_keys_org_id, and ix_events_org_source_received leads with org_id), and the referenced side
-- is the organizations PK.
ALTER TABLE sources  ADD CONSTRAINT fk_sources_org_id  FOREIGN KEY (org_id) REFERENCES organizations (id);
ALTER TABLE events   ADD CONSTRAINT fk_events_org_id   FOREIGN KEY (org_id) REFERENCES organizations (id);
ALTER TABLE api_keys ADD CONSTRAINT fk_api_keys_org_id FOREIGN KEY (org_id) REFERENCES organizations (id);
