# CON-9 — Inspector: list and view stored events (authenticated, tenant-scoped, paginated)

**Type:** feat · **Branch:** `feat/CON-9-event-inspector`

## Goal (user value)

The read side of Conduit, and the satisfying counterpart to ingest: a developer can now *see* the
webhooks Conduit stored. `GET /events` lists their org's events (paginated, newest first) and
`GET /events/{id}` shows one in full (payload + headers + metadata). Authenticated via the CON-8
API-key layer and **strictly tenant-scoped** — this is where multi-tenant isolation stops being a
design claim and becomes a *tested guarantee*. After this, Conduit has a complete receive → store →
inspect loop.

## Key design decisions (the "why")

- **Tenant isolation is the headline, not a footnote.** Every query filters by the caller's
  `org_id` (from `@AuthenticationPrincipal ApiKeyPrincipal`). `GET /events/{id}` for an event in
  *another* org returns **404, not 403** — 403 would confirm the event exists; 404 reveals nothing.
  A cross-tenant integration test (org A's key must never see org B's events) is a hard requirement.
- **Pagination is mandatory, not optional.** A real source can have millions of events; never return
  them all. Use page-based or keyset pagination — keyset (`received_at`/`id` cursor) is preferable
  for large, append-only event tables (stable under inserts, no deep-offset cost), but page-based
  (Spring `Pageable`) is acceptable for v1 if simpler. Document the choice and its trade-off.
- **List is a summary, detail is full.** `GET /events` returns lightweight rows (id, source, status-ish
  metadata, received_at, size) — NOT full payloads (could be huge × many). `GET /events/{id}` returns
  the full payload + headers. This list-vs-detail split is a standard API shape worth internalizing.
- **Payload encoding:** the payload is stored raw (`bytea`). Decide how to render it (e.g. UTF-8
  text if decodable, else base64) and set sensible response content handling; document it.
- **Read-only:** events are immutable (CON-7); these endpoints never mutate. No POST/PATCH/DELETE here.

## Acceptance criteria

- [ ] `GET /events` — authenticated; returns the caller's org's events only, **paginated**
      (newest-first), as summaries (id, source_id, received_at, byte size — not full payload).
      Supports a page size with a sane default and a hard max (e.g. default 20, max 100).
- [ ] Optional filter by `sourceId` (still org-scoped) — nice-to-have; include if cheap.
- [ ] `GET /events/{id}` — authenticated; returns the full event (payload rendered, headers,
      metadata) **only if it belongs to the caller's org**; otherwise **404**.
- [ ] Both endpoints are under the authenticated route group (no key → 401, via CON-8).
- [ ] No `org_id` is ever accepted from the client (query/body/header) — it is taken *only* from the
      authenticated principal. (Trusting a client-supplied org id would be a tenant-isolation bypass.)
- [ ] Efficient queries: the `(org_id, source_id, received_at)` index from V2 is used for the list;
      no full-table scans; no N+1.
- [ ] `./mvnw verify` green; Trivy/CodeQL gates green.

## Security criteria (shifted left — this ticket is mostly about this)

- [ ] **Cross-tenant test (REQUIRED):** seed events for org A and org B; org A's key lists only A's
      events and gets **404** for B's event id by direct lookup. This test is the proof of isolation.
- [ ] `org_id` derived solely from the principal, never from request input.
- [ ] Detail-not-found and cross-tenant-not-yours both return identical 404 (no existence leak).
- [ ] Pagination caps enforced server-side (a client asking for `size=1000000` is clamped) to
      prevent resource-exhaustion via huge pages.
- [ ] No payload/headers logged (consistent with CON-7).

## Forward plan

1. Read DTOs: `EventSummary` (list) and `EventDetail` (single).
2. Repository queries filtered by `org_id` (+ optional `sourceId`), paginated, newest-first, using
   the existing index.
3. `EventController` (`GET /events`, `GET /events/{id}`) reading `principal.orgId()`; 404 on
   not-yours/not-found; pagination params with default + hard max.
4. Payload rendering decision (text vs base64) in the detail DTO.
5. Integration tests incl. the cross-tenant isolation test, pagination bounds, 401-without-key.
6. `./mvnw verify`, curl smoke (seed events for 2 orgs, confirm isolation + paging), gates green.

## Reverse plan (matched to change type)

- **Code only (read endpoints + DTOs + queries)** → revert the squash-merge commit. Purely
  additive, read-only; nothing depends on it; **no schema change** (uses CON-7's tables/index).
- No migration, no config beyond optional pagination defaults (revert the property if added).

## Verification

- **Worked:** with a valid key, `GET /events` returns only that org's events newest-first, paged;
  `GET /events/{id}` returns a full event you own and 404 for one you don't; no key → 401;
  cross-tenant test green.
- **Failed (detect):** any cross-tenant leakage (caught by the required test), unpaginated/huge
  responses, `org_id` honored from client input, or 401 missing on no-key.

## Blast radius

Low and additive — read-only, no schema, no mutation. The one *critical* correctness property is
tenant isolation, and it's pinned by a mandatory test. Worst case: a leak the test would catch
before merge.

## Notes for the agent

Follow `CLAUDE.md`, `docs/data-model.md`, `docs/security/security-baseline.md`. The tenant-isolation
cross-org test is the heart of this ticket — make it explicit and unmistakable. Take `org_id` ONLY
from `@AuthenticationPrincipal ApiKeyPrincipal`, never from request input. Explain the pagination
choice (page vs keyset) and the list-vs-detail split. Reuse CON-7's index; avoid N+1. Read-only —
no mutations. Do not push to main; stop after pushing the branch for PR review.
