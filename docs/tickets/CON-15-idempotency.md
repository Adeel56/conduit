# CON-15 — Idempotency / dedup (don't store or deliver the same webhook twice)

**Type:** feat · **Branch:** `feat/CON-15-idempotency` · **OWNS MIGRATION V8 (if needed)**

> Build AFTER CON-13 (and ideally CON-14) merge. The `idempotency_key` was deliberately left out of
> the events table in CON-7 "for the idempotency ticket" — this is that ticket.

## Goal (value)

Webhook senders retry (it's how at-least-once works on their side too), so the *same* webhook can
arrive multiple times. Without dedup, Conduit stores duplicate Events and fans out duplicate
Deliveries. This ticket recognizes a repeat and treats it idempotently: the same incoming webhook
maps to the same single Event (and is not re-fanned-out).

## Key design decisions (the "why")

- **Idempotency via a per-source dedup key:** providers send a stable id (e.g. Stripe's event id,
  GitHub's delivery GUID) in a header, or we derive a key by hashing the raw body. On ingest, if an
  Event with that key already exists for that source, return the *existing* Event's 202-style
  response instead of creating a new one — same result, no duplicate.
- **Scoped to (source, key):** uniqueness is per source (two different sources can legitimately share
  a key value). A `UNIQUE(source_id, idempotency_key)` constraint is the real guarantee; a pre-check
  gives a friendly path, the constraint backstops the race (mirror the CON-10 route pattern — and
  use `saveAndFlush` so the race actually surfaces, per the CON-10 fix).
- **Configurable key source:** prefer a provider-supplied header (configurable name) when present;
  fall back to a content hash of the raw body. Document precedence.
- **Idempotent response:** a duplicate returns the same 202 + the original event id — the caller
  cannot tell (and shouldn't care) whether it was the first or a repeat. This is what "idempotent"
  means: same effect, any number of times.
- **Interaction with delivery (CON-13):** because a duplicate maps to the existing Event and creates
  no new Deliveries, the destination isn't re-delivered to from the duplicate. (Delivery itself is
  still at-least-once — a destination may still see retries from the delivery engine; that's
  separate and expected. Dedup here is about *ingest-side* duplicates.)

## Acceptance criteria

- [ ] Schema: add `idempotency_key` (nullable) to `events` + `UNIQUE(source_id, idempotency_key)`
      (partial/where-not-null so unsigned legacy rows aren't constrained). If a migration is needed
      it is **V8** (+ tested `U8`); confirm V6 (CON-13) and any V7 (CON-14) are taken.
- [ ] Key resolution on ingest: take the configured header if present, else hash the raw body;
      compute the per-source key.
- [ ] Dedup logic: if `(source_id, idempotency_key)` already exists → return the existing Event's
      202 response, create NO new Event and NO new Deliveries. Else store as normal (CON-7 path).
- [ ] Race-safe: pre-check + `saveAndFlush` + catch the unique violation → treat as duplicate
      (return the now-existing Event). No 500 on the race (apply the CON-10 lesson).
- [ ] Backward compatible: requests without a resolvable key (and existing rows) behave as today.
- [ ] Integration tests (Testcontainers): same webhook twice → one Event, one set of Deliveries,
      both responses reference the same event id; different bodies → two Events; concurrent
      duplicates → still one Event (race); a request with no key → stored normally.
- [ ] `./mvnw verify` green; round-trip if a migration was added; Trivy/CodeQL green.

## Security criteria

- [ ] `org_id`/`source_id` still set from the source (dedup never crosses tenants — key is per source).
- [ ] No payloads/secrets logged (the key/hash is fine to log at debug as an id; not the body).
- [ ] A malicious caller can't poison another source's events (key is scoped to the resolved source).

## Forward plan

1. `V8` (+ `U8`): `events.idempotency_key` + partial unique `(source_id, idempotency_key)`.
2. Key resolver (header-or-hash, configurable, per source).
3. Ingest dedup: pre-check → store with `saveAndFlush` → on unique violation, return existing.
4. Confirm no duplicate Deliveries are fanned out for a duplicate ingest (ties to CON-13).
5. Tests: dup→one, distinct→two, concurrent-dup→one, no-key→stored.
6. `./mvnw verify`, round-trip, gates.

## Reverse plan (matched to change type)

- **Schema** → `U8` drops the unique constraint then the column (constraint before column). Nullable
  + partial constraint means existing rows are unaffected by add or drop.
- **Code** → revert; ingest returns to CON-7 store-every-time behaviour.
- **Behavioral:** turning dedup on can't lose data (worst case = a duplicate stored, i.e. today's
  behaviour). Turning it off just stops collapsing duplicates. Low-risk both directions.

## Verification

- **Worked:** the same webhook posted twice yields one Event and one fan-out; distinct webhooks
  yield two; concurrent duplicates collapse to one (no 500); keyless requests still store.
- **Failed (detect):** duplicate Events for the same (source,key), duplicate Deliveries from a
  repeat, a 500 on the concurrent-duplicate race, or cross-source key collisions.

## Blast radius

Low–medium. Touches the ingest path again, but additive and backward-compatible (nullable column,
partial constraint, keyless requests unchanged). The race is the one sharp edge — handled by the
CON-10 `saveAndFlush`+catch pattern and pinned by a concurrency test.

## Notes for the agent

Follow `CLAUDE.md`, `docs/data-model.md` (the deferred `idempotency_key` lands here), ADR-0003
(at-least-once + idempotency is the safe-retry half of it). Reuse the CON-10 duplicate-handling
pattern *including the saveAndFlush fix*. Confirm migration numbering against V6/V7 before taking
**V8**. Explain idempotency (same effect any number of times) and the per-(source,key) scope. Verify
a duplicate ingest creates no new Deliveries (ties into CON-13). Reversible migration mandatory. Do
not push to main; stop after pushing the branch for PR review.
