-- V2 — first real schema (CON-7): `sources` (webhook receive endpoints) and `events` (immutable
-- records of received webhooks). Shape per docs/data-model.md.
--
-- REVERSIBILITY: Flyway Community has no automatic undo. The tested reverse path lives at
-- src/main/resources/db/undo/U2__sources_and_events.sql (kept OUT of db/migration so Flyway never
-- runs it). Locally, `docker compose down -v` wipes everything. See the PR's reverse plan.
--
-- org_id is a plain UUID for now (no FK): the `organizations` table arrives with the org/auth
-- ticket, which will add the FK. Until then the application always sets org_id from the owning
-- source, so events are never orphaned (tenant isolation — docs/data-model.md).

CREATE TABLE sources (
    id          UUID        PRIMARY KEY,
    org_id      UUID        NOT NULL,
    name        TEXT        NOT NULL,
    -- High-entropy, unguessable secret used as the public ingest URL piece (POST /ingest/{ingest_key}).
    -- NOT the id (ids are enumerable and would leak the source count). Unique + indexed for lookup.
    ingest_key  TEXT        NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_sources_ingest_key ON sources (ingest_key);
CREATE INDEX ix_sources_org_id ON sources (org_id);

CREATE TABLE events (
    id           UUID        PRIMARY KEY,
    org_id       UUID        NOT NULL,
    source_id    UUID        NOT NULL REFERENCES sources (id),
    payload      BYTEA       NOT NULL,   -- raw request body, stored faithfully and never parsed
    headers      JSONB       NOT NULL,   -- request headers as received (JSON object of name -> value)
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No updated_at: an Event is immutable (docs/data-model.md) — written once, never mutated.
    -- idempotency_key is intentionally NOT here yet; it lands with the idempotency ticket (V3+).
);

-- Inspector query path (data-model indexing notes): an org's events for a source, newest first.
CREATE INDEX ix_events_org_source_received ON events (org_id, source_id, received_at);
