# CON-7 — Ingest endpoint: receive a webhook and store it as an Event

**Type:** feat · **Branch:** `feat/CON-7-ingest-endpoint`

## Goal (user value)

Conduit's front door. A third party (Stripe/GitHub) can POST a webhook to a unique per-source URL;
Conduit durably stores it as an immutable `Event` and immediately acknowledges with `202 Accepted`.
This is the first feature that makes Conduit *do its job* — after this you can `curl` a payload and
see it persisted. Background delivery to destinations is a later ticket; this ticket is
**receive + store + ack**.

## Key design decisions (the "why")

- **URL / source identity:** `POST /ingest/{ingestKey}`. `ingestKey` is a high-entropy,
  URL-safe, unguessable token on the `Source` (NOT the DB id — ids are enumerable and leak source
  count). The secret URL is how the request is routed; real authenticity (HMAC) is a later ticket.
- **Receive-fast, deliver-later:** store the event, then return **`202 Accepted`** immediately. Do
  NOT deliver synchronously — webhook senders have short timeouts; slow synchronous delivery would
  cause sender-side timeouts and retry storms. Decoupling receive from deliver is the core
  reliability pattern. (Delivery via queue/workers = future ticket.)
- **Store raw, don't trust:** persist the body exactly as received (no parsing/transformation) plus
  the request headers (providers carry signature + event-type metadata there). Faithful storage is
  the product's point.
- **Public endpoint → abuse protection:** cap body size (reject > a configured limit, e.g. 1 MB,
  with `413`). Do the cap check before expensive work.

## Acceptance criteria

- [ ] **Schema (first real migration):** Flyway `V2__sources_and_events.sql` creating `sources` and
      `events` tables per `docs/data-model.md` (with `org_id`, the `ingest_key` unique+indexed on
      sources, `events` referencing `source_id`, raw `payload`, `headers`, `received_at`). Ships
      with a tested **down/undo** path documented (reversibility) — Flyway community has no auto
      down-migrations, so provide a `U2__*.sql` *or* document the manual reverse in the PR.
- [ ] JPA entities `Source` and `Event` (+ repositories). `ddl-auto` stays `validate` (Flyway owns
      schema).
- [ ] `POST /ingest/{ingestKey}` controller:
      - unknown or inactive `ingestKey` → **404** (identical response for "missing" vs "inactive"
        so existence isn't revealed).
      - body over the size cap → **413**.
      - valid + within cap → store an `Event` (source_id, org_id, raw body, headers, received_at),
        return **202** with a minimal body (e.g. the new event id).
- [ ] Body size cap is configurable via env/properties (12-factor), with a sane default.
- [ ] For testing, a way to create a `Source` with an `ingestKey` (a minimal seed, a test-only
      helper, or a tiny admin endpoint — keep it minimal; full source-management CRUD is a later
      ticket).
- [ ] Integration test (Testcontainers): POST to a known ingestKey → 202 and a row in `events`;
      unknown key → 404; oversize body → 413; tenant fields (`org_id`) populated correctly.
- [ ] `./mvnw verify` green; Trivy/CodeQL gates green.

## Security criteria (shifted left)

- [ ] `ingestKey` is high-entropy and generated securely (CSPRNG), stored so it can be looked up.
- [ ] No parsing/eval of the untrusted body; store as bytes/text faithfully.
- [ ] Size cap enforced to prevent memory-exhaustion abuse.
- [ ] `org_id` set from the source on every event (tenant isolation — events are never orphaned).
- [ ] 404 response does not leak whether a key exists vs is inactive.

## Forward plan

1. Flyway `V2` migration: `sources` + `events` tables (+ indexes, `org_id`, unique `ingest_key`).
2. JPA entities + repositories for `Source` and `Event`.
3. Ingest controller: resolve source by `ingestKey`, enforce size cap, store event, return 202.
4. Minimal source-creation path for testing.
5. Integration tests for the 202 / 404 / 413 paths + tenant fields.
6. `./mvnw verify`, local `curl` smoke test, gates green.

## Reverse plan (matched to change type)

- **Code (controller/entities)** → revert the squash-merge commit.
- **Schema change** → this is the first real migration, so the reverse path matters: provide a
  tested undo (`U2` or documented manual `DROP`), and note that on a fresh/local DB
  `docker compose down -v` wipes it entirely. Document the production-style reverse explicitly.
- **Config (size cap)** → revert the property; default is safe.

## Verification

- **Worked:** `curl -X POST .../ingest/<key> -d '{...}'` → 202; a row appears in `events` with the
  raw body + headers + correct `org_id`; unknown key → 404; oversize → 413; integration tests green.
- **Failed (detect):** 5xx on ingest (logs), event not stored, wrong/empty `org_id`, or tests red.

## Blast radius

First feature + first real schema. Additive (new tables, new endpoint) — nothing existing depends
on it yet, so revert is clean. The new migration is the main thing to get right; the down path and
the `docker compose down -v` reset bound the risk locally.

## Notes for the agent

Follow `CLAUDE.md`, `docs/data-model.md`, `docs/security/security-baseline.md`. Keep scope to
receive+store+ack — NO HMAC, NO delivery, NO idempotency yet (each is a separate future ticket); if
something seems to need them, flag it, don't build it. Explain the migration, the entity mapping,
and the 202/async reasoning so every line is understandable. Reversible migration is mandatory. Do
not push to main; stop after pushing the branch for PR review.
