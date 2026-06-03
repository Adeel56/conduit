-- V5 — destinations and routes (CON-10): the delivery targets and the wiring that connects a
-- `source` to a `destination`. Shape per docs/data-model.md / ADR-0008.
--
-- A `Destination` is an org-owned, reusable outbound URL Conduit will later POST to (CON-13). A
-- `Route` is the many-to-many join: "events arriving on this source go to this destination". One
-- source can fan out to many destinations and one destination can serve many sources, so the pair
-- is modeled as a join row (which is also the future home for per-route config). This ticket only
-- defines the targets/wiring — it does NOT deliver anything (no HTTP, no queue; all CON-13).
--
-- TENANT ISOLATION: both tables carry org_id with a real FK to organizations (the FK is real now,
-- after CON-12's V4). A route may only join a source and a destination that belong to the SAME org;
-- that same-org rule is enforced in the application at route-creation time — a route spanning two
-- tenants would be a cross-tenant isolation hole (docs/data-model.md). The DB still guarantees each
-- row's org_id, source_id, and destination_id reference real rows via the FKs below.
--
-- REVERSIBILITY: Flyway Community runs no undo scripts. The tested reverse lives at
-- src/main/resources/db/undo/U5__destinations_and_routes.sql (kept OUT of db/migration so Flyway
-- never runs it). It drops the FKs BEFORE the tables, in dependency order — mirror of this file.
-- Locally, `docker compose down -v` wipes everything. See the PR's reverse plan.

CREATE TABLE destinations (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations (id),
    name       TEXT        NOT NULL,
    -- User-supplied outbound URL Conduit will POST to. Validated as an absolute http/https URL in
    -- the application before insert; SSRF hardening (blocking internal/link-local/metadata ranges)
    -- is a deliberately separate follow-up ticket (see the ticket's SSRF note).
    url        TEXT        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tenant-scoped lookups (list/get destinations for an org) lead with org_id.
CREATE INDEX ix_destinations_org_id ON destinations (org_id);

CREATE TABLE routes (
    id             UUID        PRIMARY KEY,
    org_id         UUID        NOT NULL REFERENCES organizations (id),
    source_id      UUID        NOT NULL REFERENCES sources (id),
    destination_id UUID        NOT NULL REFERENCES destinations (id),
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One wiring per (source, destination): prevents duplicate routes (the same event would otherwise
-- fan out to the same destination twice). The unique index also serves the "routes for a source"
-- lookup (it leads with source_id), so no separate source_id index is needed.
CREATE UNIQUE INDEX ux_routes_source_destination ON routes (source_id, destination_id);

-- Tenant-scoped list of an org's routes leads with org_id.
CREATE INDEX ix_routes_org_id ON routes (org_id);

-- The delivery engine (CON-13) will, given an event's destination, find its routes; index the FK
-- the unique index does not already lead with.
CREATE INDEX ix_routes_destination_id ON routes (destination_id);
