# CON-11 — Source management CRUD (create/list/update/rotate/deactivate sources)

**Type:** feat · **Branch:** `feat/CON-11-source-crud` · **OWNS MIGRATION V6 (only if needed)**
**Parallel wave (ADR-0009):** runs concurrently with CON-10 (which owns V5). Do NOT consume V5.

## Goal (value)

Today a `Source` can only be created via an internal service method the tests call — there's no way
for a user to manage their own sources. This ticket adds authenticated, tenant-scoped CRUD so a user
can create a source (and receive its ingest URL/key once), list their sources, view/update one, and
deactivate it. This makes the ingest side actually usable by a real user, and complements the
inspector on the management surface.

## Key design decisions (the "why")

- **CRUD behind auth (CON-8), tenant-scoped:** every endpoint requires an API key and uses
  `principal.orgId()`. `org_id` comes only from the principal, never request input. 404 for
  not-yours-or-not-found (consistent with the inspector / CON-9).
- **Ingest key shown once:** creating a source generates its high-entropy `ingestKey` (already done
  in CON-7's generator) and returns the full ingest URL/key to the caller **once** — listing/getting
  a source afterward shows only a non-secret identifier (e.g. a prefix or the source id), never the
  full key again. (Same "show-once" principle as API keys in CON-8.)
- **Key rotation:** support rotating a source's ingest key (issues a new key, invalidates the old) —
  important operationally if a key leaks. Rotation returns the new key once.
- **Deactivate, don't hard-delete:** deactivating a source stops ingest (CON-7 already 404s inactive
  sources) but preserves its events (immutable history). Hard delete is out of scope (would orphan
  events); note it.
- **Whether a migration is needed:** CON-7's `sources` table may already suffice. Only add a
  migration **if** rotation/management needs a new column (e.g. `ingest_key_rotated_at`, or a
  separate key table). **If a migration IS needed it OWNS V6** (CON-10 owns V5 — do not take V5). If
  no schema change is needed, add no migration. Decide and document.

## Acceptance criteria

- [ ] Source CRUD endpoints (authenticated, org-scoped):
      - **create** → returns the source + its full ingest key/URL **once**.
      - **list** (paginated, default 20 / max 100) → summaries, no full ingest key.
      - **get-by-id** → 404 if not yours; no full ingest key.
      - **update** (e.g. name) → org-scoped.
      - **rotate key** → issues a new ingest key, invalidates old, returns new key once.
      - **deactivate** → source set inactive; ingest to it then 404s (CON-7 behavior).
- [ ] `org_id` only from the principal; 404 for not-yours-or-not-found (no existence leak).
- [ ] If a schema change is required for rotation/management, **`V6__*.sql`** (+ tested **`U6`**);
      otherwise none. Document the decision either way.
- [ ] Reuse existing `Source` entity / `SourceService` / `IngestKeyGenerator`; extend rather than
      duplicate.
- [ ] Integration tests (Testcontainers): create-returns-key-once; list/get hide the full key;
      **cross-tenant**: a key for org A cannot see/update/rotate/deactivate org B's source (404);
      deactivate then ingest → 404; rotate then old key → 404 and new key → 202; pagination bounds.
- [ ] `./mvnw verify` green; round-trip tested if a migration was added; Trivy/CodeQL green.

## Security criteria

- [ ] Ingest key generated via the existing CSPRNG generator; full key shown once, never re-shown,
      never logged.
- [ ] Rotation immediately invalidates the old key (old key → 404 on ingest).
- [ ] `org_id` only from principal; cross-tenant management blocked (404).
- [ ] No secrets/payloads logged.

## Forward plan

1. Decide migration need (rotation metadata?). If yes, `V6` (+ `U6`); else none. Document.
2. Source management service methods (create-returns-key, update, rotate, deactivate) extending
   `SourceService`.
3. Source CRUD controller (authenticated, org-scoped, paginated).
4. Integration tests incl. cross-tenant + rotate/deactivate behavior against ingest.
5. `./mvnw verify`, round-trip (if migration), gates.

## Reverse plan (matched to change type)

- **Code** → revert the squash-merge.
- **Schema (only if V6 added)** → tested `U6` reverse; documented prod reverse; `docker compose
  down -v` locally.
- **Behavioral:** new endpoints are additive; existing ingest/auth/inspector unaffected.

## Verification

- **Worked:** full source lifecycle via API, org-scoped; key shown once; rotation invalidates old;
  deactivate stops ingest; cross-tenant blocked; tests green.
- **Failed (detect):** full key leaked on list/get, cross-tenant management allowed, rotated old key
  still works, or deactivate doesn't stop ingest.

## Blast radius

Mostly additive (new endpoints; possibly one column). Touches the existing `source` package — the
area to be careful in. Reuses CON-7 plumbing, so low risk. Cross-tenant + show-once are the
properties to pin with tests.

## Notes for the agent — PARALLEL WAVE COORDINATION

Follow `CLAUDE.md`, `docs/data-model.md`, ADR-0008, ADR-0009. **CON-10 owns migration V5 — do NOT
create or touch V5.** If you need a migration, it is **V6**. You work in the existing `source`
package + a new controller; CON-10 works in a new `destination`/`route` package — you should not
collide. Do NOT change `SecurityConfig` (it already authenticates all non-public routes); if you
think you must, STOP and flag it (CON-10 is told the same, so neither of you edits it). Reuse the
inspector's auth + pagination patterns. Reversible migration mandatory if added. Do not push to main;
stop after pushing the branch for PR review.
