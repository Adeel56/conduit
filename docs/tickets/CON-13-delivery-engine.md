# CON-13 — Delivery engine (queue + workers + retries; at-least-once delivery)

**Type:** feat (the centerpiece) · **Branch:** `feat/CON-13-delivery-engine` · **OWNS MIGRATION V6**

## Goal (value)

Turn Conduit from a recorder into a **relay**. When an event is ingested, for each Route on its
source a **Delivery** is created and attempted: Conduit POSTs the event to the destination URL,
retries with backoff on failure, and records every Attempt. This is the heart of the product and
the biggest concurrency piece — the thing the whole architecture (202-accept, virtual threads) was
designed for.

## Key design decisions (the "why") — READ THE STUDY GUIDE PART 2 ALONGSIDE THIS

- **At-least-once, never exactly-once (ADR-0003):** exactly-once over a network is impossible. We
  guarantee at-least-once + make retries safe; true dedup is CON-15's job. A destination may receive
  a duplicate; that's expected and documented.
- **Delivery holds status, NOT Event (ADR-0008):** one event fans out to many destinations, each
  with its own success/failure. So a per-(event,route) **Delivery** row carries `status` and
  `retry_count`; an **Attempt** row records each individual try (timestamp, response code, error).
  The Event stays immutable.
- **Decoupled from ingest:** ingest already returns 202 and stores the Event (CON-7). Delivery
  happens *after*, asynchronously — a slow/failing destination must never affect ingest latency.
- **Backoff with jitter:** failed deliveries retry on an exponential schedule (e.g. 1s, 4s, 16s, …
  capped), with jitter to avoid thundering-herd. After N attempts, the Delivery is marked `failed`
  (dead-letter — replayable later).
- **Virtual threads (ADR-0004):** delivery is blocking HTTP I/O. Virtual threads let simple
  synchronous code scale to many concurrent in-flight deliveries without a reactive rewrite.
- **Worker claims work safely:** multiple workers (and later, multiple instances) must not deliver
  the same Delivery twice in parallel. Use a DB-backed claim — `SELECT ... FOR UPDATE SKIP LOCKED`
  (Postgres) or a status transition (`pending → in_flight` via a guarded update) — so each pending
  Delivery is claimed by exactly one worker at a time. (This is the concurrency crux — explain it.)

## Acceptance criteria

- [ ] Flyway **`V6__deliveries_and_attempts.sql`** (+ tested **`U6`**): `deliveries`
      (`id`, `org_id` FK, `event_id` FK→events, `route_id` FK→routes, `destination_id` FK,
      `status` [pending|in_flight|delivered|failed], `attempt_count`, `next_attempt_at`,
      `created_at`, `updated_at`) and `attempts` (`id`, `delivery_id` FK, `attempted_at`,
      `response_status` nullable, `error` nullable, `duration_ms`). Indexes for the worker's claim
      query (e.g. `status, next_attempt_at`) and for listing by delivery.
- [ ] JPA entities `Delivery` + `Attempt` (+ repositories). `ddl-auto` stays `validate`.
- [ ] **Fan-out on ingest:** when an Event is stored, a `pending` Delivery is created for each active
      Route on that source. (Do this *after* the 202 — e.g. an after-commit hook or a claim the
      worker picks up — never synchronously in the ingest request.)
- [ ] **Worker loop:** claims due `pending` Deliveries (`status=pending AND next_attempt_at<=now`),
      marks `in_flight` (atomic claim — no double-delivery), POSTs the event payload + headers to the
      destination URL, records an Attempt, and on success → `delivered`; on failure → schedule the
      next retry (`attempt_count++`, `next_attempt_at = now + backoff(attempt_count)`) or, past the
      cap, → `failed`.
- [ ] **Backoff** is exponential with jitter and a max attempt cap; both configurable (12-factor).
- [ ] **HTTP client** with sane connect/read timeouts (a hung destination must not hang a worker
      forever) and a bounded response read.
- [ ] **Concurrency safety:** a test proving two workers don't both deliver the same Delivery
      (claim is exclusive). A test proving a failing destination retries then dead-letters.
- [ ] **Read API (authenticated, org-scoped):** `GET /deliveries` (paginated, filter by event/status)
      and `GET /deliveries/{id}` (with its attempts) — reuse the inspector's auth + pagination + 404
      patterns; org_id only from principal.
- [ ] Integration tests (Testcontainers + a stub HTTP destination, e.g. a WireMock/embedded server):
      happy-path delivery (202-stored event → delivered), retry-then-success, retry-then-dead-letter,
      fan-out (one event → multiple destinations → multiple deliveries), and the concurrency claim
      test. **Cross-tenant test** on the deliveries read API.
- [ ] `./mvnw verify` green; V6→U6→V6 round-trip; Trivy/CodeQL green.

## Security criteria

- [ ] `org_id` set from the event/route on every Delivery; deliveries read API org-scoped (404).
- [ ] Outbound POST does NOT follow redirects to internal addresses (basic SSRF guard) — or note
      SSRF hardening as its own follow-up if out of scope here; at minimum, timeouts + no redirect
      chasing to private ranges.
- [ ] No payloads/secrets logged (ids, status, response codes only).
- [ ] Worker claim is race-safe (no double-delivery) — proven by test.

## Forward plan

1. `V6` migration (+ `U6`): deliveries, attempts, indexes, FKs.
2. `Delivery` + `Attempt` entities/repos.
3. Fan-out: on event store (after-commit), create pending Deliveries for active routes.
4. Worker: claim (SKIP LOCKED / guarded status update) → deliver → record Attempt → success or
   reschedule/dead-letter. Run on a scheduled poller and/or triggered, on virtual threads.
5. Backoff config + HTTP client with timeouts.
6. Deliveries read API (list + detail with attempts), org-scoped.
7. Tests: happy, retry→success, retry→dead-letter, fan-out, concurrency-claim, cross-tenant.
8. `./mvnw verify`, round-trip, gates.

## Reverse plan (matched to change type)

- **Schema** → tested `U6` (drop FKs/indexes, then `attempts`, then `deliveries`, dependents first).
- **Code (worker/fan-out)** → revert the squash-merge. The worker is additive; with it reverted,
  ingest still stores events (CON-7 unaffected) — delivery simply stops happening.
- **Config (backoff/timeouts/poll interval)** → revert properties; safe defaults.
- **Behavioral:** the worker is the one piece that *acts on the world* (outbound HTTP). Consider a
  feature flag (`conduit.delivery.enabled`) so delivery can be turned off without a deploy — note it.

## Verification

- **Worked:** ingest an event for a source wired to a stub destination → a Delivery goes
  `pending → in_flight → delivered`, an Attempt row records the 2xx; a failing stub → retries on
  schedule then `failed`; one event with two routes → two Deliveries; two workers never double-deliver.
- **Failed (detect):** duplicate deliveries (claim race), deliveries stuck `in_flight` (no timeout
  recovery), unbounded retries, ingest latency rising (delivery leaking into the request path), or
  any cross-tenant leak on the read API.

## Blast radius

**Highest of any ticket so far** — it's the first code that makes outbound network calls and runs
background concurrency. A claim bug = double-delivery; a missing timeout = stuck workers; delivery
in the request path = ingest slowdown. Mitigations: the exclusive-claim test, HTTP timeouts, the
delivery-off flag, and keeping fan-out strictly post-commit. The schema is reversible via U6.

## Notes for the agent

Follow `CLAUDE.md`, `docs/data-model.md`, ADR-0003 (at-least-once), ADR-0004 (virtual threads),
ADR-0008. **OWNS V6.** Explain the worker claim mechanism (SKIP LOCKED vs guarded status update) and
the backoff schedule clearly — this is the conceptual core. Keep delivery strictly decoupled from
ingest (post-commit fan-out, never in the 202 path). Use a real stub HTTP server in tests, not a
mock, so timeouts/retries are genuinely exercised. Reversible migration mandatory. Consider a
`conduit.delivery.enabled` flag. Do not push to main; stop after pushing the branch for PR review.
This is the centerpiece — bias toward clarity and test coverage over cleverness.
