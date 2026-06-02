-- V3 — api_keys (CON-8): authenticate API/dashboard callers and resolve their organization.
-- Shape per docs/data-model.md. Reverse path: src/main/resources/db/undo/U3__api_keys.sql.
--
-- org_id is a plain UUID (no FK), consistent with V2's sources.org_id: the `organizations` table
-- (and FKs from sources/api_keys) arrives with the org-management ticket. Interim is documented and
-- deliberate — the application always sets org_id, and a valid key resolves exactly one org.

CREATE TABLE api_keys (
    id           UUID        PRIMARY KEY,
    org_id       UUID        NOT NULL,
    name         TEXT        NOT NULL,
    -- Non-secret public identifier (the part before the '.' in the presented key). Shown in the UI
    -- to tell keys apart, and the narrow lookup key: find by prefix, then verify the hash.
    key_prefix   TEXT        NOT NULL,
    -- Salted one-way hash of the secret half ("<saltB64>:<hashB64>"). We only ever verify a
    -- presented key, never read it back — so the raw secret is never stored.
    key_hash     TEXT        NOT NULL,
    scopes       TEXT        NOT NULL,   -- least-privilege scopes (comma-separated); modeled now
    last_used_at TIMESTAMPTZ,            -- nullable: updated on successful auth
    revoked_at   TIMESTAMPTZ,            -- nullable: soft revoke (auditable); revoked keys fail auth
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- key_prefix is the per-request lookup key — unique and indexed.
CREATE UNIQUE INDEX ux_api_keys_key_prefix ON api_keys (key_prefix);
CREATE INDEX ix_api_keys_org_id ON api_keys (org_id);
