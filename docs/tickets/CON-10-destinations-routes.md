# CON-10 — Destinations & Routes (where webhooks go)

**Type:** feat · **Branch:** `feat/CON-10-destinations-routes` · **OWNS MIGRATION V5**
**Parallel wave (ADR-0009):** runs concurrently with CON-11 (which owns V6). Do NOT consume V6.

## Goal (value)

Conduit can receive and inspect webhooks, but has nowhere to *send* them. This ticket adds the
delivery targets: a **`Destination`** (an outbound URL Conduit will POST to) and a **`Route`** (the
join that wires a `Source` to a `Destination`, so an event arriving on that source knows where it
should be delivered). This is the wiring the delivery engine (CON-13) will read — it does NOT
deliver anything yet (no HTTP calls out). Receive → store → inspect already works; this defines the
"→ deliver to whom" without doing the delivering.

## Key design decisions (the "why")

- **Destinations and Routes, per the data model (ADR-0008):** `Source` ↔ `Destination` is
  many-to-many via `Route` (one source can fan out to many destinations; one destination can serve
  many sources). The `Route` is the join row, and it's where per-wiring config lives later.
- **Tenant-scoped throughout:** `Destination` and `Route` both carry `org_id` (FK to organizations,
  now real after CON-12). A route may only join a source and destination *in the same org* — a route
  spanning two tenants would be a data-isolation hole. Enforce same-org at creation.
- **CRUD behind auth (CON-8):** management endpoints require an API key and are tenant-scoped via
  `principal.orgId()` — same pattern as the inspector. `org_id` only ever from the principal.
- **No delivery yet:** this ticket defines targets and wiring only. No outbound HTTP, no queue, no
  retries — all CON-13. If something seems to need them, flag it, don't build it.

## Acceptance criteria

- [ ] Flyway **`V5__destinations_and_routes.sql`** (+ tested **`U5`** reverse): `destinations`
      (`id`, `org_id` FK→organizations, `name`, `url`, `active`, timestamps) and `routes`
      (`id`, `org_id` FK→organizations, `source_id` FK→sources, `destination_id` FK→destinations,
      `active`, timestamps; unique on `(source_id, destination_id)` to prevent duplicate wiring),
      with indexes for the lookups the delivery engine will need (e.g. by `source_id`).
- [ ] JPA `Destination` and `Route` entities + repositories.
- [ ] Destination CRUD endpoints (authenticated, org-scoped): create, list (paginated), get-by-id
      (404 if not yours), update, deactivate. Reuse the inspector's pagination conventions
      (default 20, max 100).
- [ ] Route endpoints (authenticated, org-scoped): create a route (validating that the source AND
      the destination both exist *and belong to the caller's org* — reject cross-tenant or unknown
      with 404/400 as appropriate, without leaking existence), list routes (optionally by source),
      delete/deactivate a route.
- [ ] URL validation on destinations (must be a valid absolute http/https URL); reject obviously
      invalid. (SSRF hardening — blocking internal addresses — can be its own later ticket; note it.)
- [ ] Integration tests (Testcontainers): destination CRUD happy paths; route creation success;
      **cross-tenant rejection** (can't route to another org's destination; can't see another org's
      destinations/routes — 404); duplicate-route rejected by the unique constraint; pagination bounds.
- [ ] `./mvnw verify` green; V5→U5→V5 round-trip tested; Trivy/CodeQL green.

## Security criteria

- [ ] `org_id` only from the authenticated principal — never request input.
- [ ] Same-org enforcement on route creation (source, destination, route all one tenant).
- [ ] 404 for not-yours-or-not-found (no existence leak), consistent with the inspector.
- [ ] Destination URL validated; SSRF note recorded for a follow-up.
- [ ] No secrets/payloads logged.

## Forward plan

1. `V5` migration (+ `U5`): destinations, routes, FKs to organizations/sources/destinations, unique
   `(source_id, destination_id)`, indexes.
2. Entities + repositories.
3. Destination CRUD controller + service (org-scoped, paginated).
4. Route controller + service (same-org validation on create).
5. Integration tests incl. cross-tenant rejection + duplicate-route constraint.
6. `./mvnw verify`, round-trip, gates.

## Reverse plan (matched to change type)

- **Schema** → tested `U5`: drop FKs/constraints, then `routes`, then `destinations` (respect FK
  order). Documented prod reverse: run U5, delete `flyway_schema_history` v5. `docker compose down -v`
  locally.
- **Code** → revert the squash-merge.

## Verification

- **Worked:** destinations/routes CRUD works org-scoped; cross-tenant attempts 404; duplicate route
  rejected; tests green; round-trip clean.
- **Failed (detect):** any cross-tenant leak (caught by test), route spanning orgs allowed, or
  migration/round-trip failure.

## Blast radius

Additive (two new tables, new endpoints) — nothing existing depends on it yet (CON-13 will). The
same-org route validation is the one correctness property to get right; it's pinned by a test.

## Notes for the agent — PARALLEL WAVE COORDINATION

Follow `CLAUDE.md`, `docs/data-model.md`, ADR-0008, ADR-0009. **This ticket OWNS migration V5 only**
— CON-11 owns V6 and is being built in parallel; do NOT create V6 or touch source-management CRUD
(that's CON-11's). Stay in a new `destination`/`route` package + your own migration; avoid editing
shared files (SecurityConfig already permits authenticated routes via `anyRequest().authenticated()`,
so you should NOT need to change it — if you think you do, STOP and flag it, since CON-11 may touch
nearby areas). Reuse the inspector's auth + pagination patterns. Reversible migration mandatory. Do
not push to main; stop after pushing the branch for PR review.
