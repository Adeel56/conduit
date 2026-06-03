# CON-12 — Organizations table (the real tenant entity behind org_id)

**Type:** feat (foundational data-model) · **Branch:** `feat/CON-12-organizations` · **Migration: V4**

## Goal (value)

Turn `org_id` from a loose, unbacked UUID into a real **`organizations`** row. Every tenant-scoped
table (`sources`, `events`, `api_keys`) currently carries an `org_id` that points at nothing. This
ticket creates the `organizations` table, backfills/links existing data, and makes `org_id` a real
foreign key. It is the foundation the upcoming CRUD wave (sources, destinations) builds on — so it
ships **first and alone** before that wave parallelizes (ADR-0009).

## Key design decisions (the "why")

- **Foundational-first (ADR-0009):** sources/destinations/api-keys all belong to an org. Introducing
  the org entity now means the parallel CRUD wave builds on a stable contract instead of each
  feature re-guessing the org model.
- **Real FK, real integrity:** `org_id` becomes a FK to `organizations(id)`. A source/event/api-key
  can no longer reference a non-existent tenant — the database enforces tenant integrity.
- **Minimal org for now:** `organizations` starts small (`id`, `name`, `slug`, timestamps). User
  membership, roles, billing, etc. are their own later tickets — don't build them here.
- **Backfill safely:** existing rows already have `org_id` values. The migration must create
  `organizations` rows for the distinct existing `org_id`s *before* adding the FK constraint, or the
  constraint fails. Order matters and must be in the migration.

## Acceptance criteria

- [ ] Flyway **`V4__organizations.sql`** (+ tested **`U4`** reverse): create `organizations`
      (`id` UUID PK, `name`, `slug` unique, `created_at`, `updated_at`); **backfill** a row for every
      distinct existing `org_id` in `sources`/`events`/`api_keys`; then add FK constraints
      `org_id → organizations(id)` on those tables. Index where appropriate.
- [ ] JPA `Organization` entity + repository.
- [ ] A minimal seed path (service method) to create an organization for tests — no unauthenticated
      org-creation endpoint (full org management is a later ticket).
- [ ] Existing functionality unaffected: ingest, auth, inspector all still work (their `org_id`s now
      resolve to real org rows). `ddl-auto` stays `validate`.
- [ ] Integration tests: an org can be created; a source/event/api-key references a real org;
      attempting to insert a tenant row with a non-existent `org_id` fails the FK (proves integrity);
      the existing suites stay green.
- [ ] `./mvnw verify` green; reverse round-trip (V4 → U4 → V4) tested; Trivy/CodeQL green.

## Security criteria

- [ ] FK integrity prevents orphaned tenant data (a row can't belong to a non-existent org).
- [ ] No cross-tenant behavior changes — this strengthens, never loosens, isolation.
- [ ] Backfill is deterministic and idempotent-safe (re-running the migration logic wouldn't dup).

## Forward plan

1. `V4` migration: create `organizations`; backfill from distinct existing `org_id`s; add FKs.
2. `Organization` entity + repository; org-creation service for tests/seed.
3. Update existing seed/test helpers (SourceService, ApiKey seed) to create/reference a real org.
4. Integration tests incl. the FK-integrity test.
5. `./mvnw verify`, reverse round-trip, gates.

## Reverse plan (matched to change type)

- **Schema (FKs + new table)** → tested `U4`: drop the FK constraints first, then drop
  `organizations`. Documented prod reverse: run U4, delete `flyway_schema_history` row v4.
  `docker compose down -v` locally. **The FK-drop-before-table-drop order is the thing to get right.**
- **Code (entity/service)** → revert the squash-merge.

## Verification

- **Worked:** org rows exist for all prior `org_id`s; FKs enforced (bad `org_id` insert fails);
  all existing features still green; reverse round-trip clean.
- **Failed (detect):** migration fails on backfill/FK (caught locally), existing suites red, or an
  `org_id` somewhere doesn't resolve to a real org.

## Blast radius

Medium — it adds FK constraints to existing tables, so a botched backfill would fail the migration
(loudly, before merge). Additive otherwise. The tested U4 + `docker compose down -v` bound it.
**This is the riskiest migration so far** (it constrains existing data), so the backfill ordering and
the reverse get extra scrutiny.

## Notes for the agent (and for the parallel wave that follows)

Follow `CLAUDE.md`, `docs/data-model.md`, ADR-0008, ADR-0009. **This ticket OWNS migration V4** — the
next wave (CON-10 destinations/routes, CON-11 source CRUD) will own V5 and V6 respectively; do not
consume those. Keep the org entity minimal. Get the backfill-before-FK ordering right and explain it.
Reversible migration mandatory (FK-drop-before-table-drop). Do not push to main; stop after pushing
the branch for PR review.
