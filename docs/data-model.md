# Conduit — Data Model (Design)

The skeleton everything hangs off. See ADR-0008 for *why* it is shaped this way. This doc is the
*what*: entities, key columns, relationships, and how tenant isolation is enforced.

## The shape, in one read

`Organization` is the tenant. It owns `User`, `ApiKey`, `Source`, `Destination`. A `Route`
connects a `Source` to a `Destination` (many-to-many). A webhook arriving at a `Source` is stored
as an immutable `Event`. For each route from that source, a `Delivery` is created (the effort to
get one event to one destination — it holds the status and retry count). Each `Delivery`
accumulates `Attempt` rows (one per try).

## Tenant isolation (the make-or-break rule)

Conduit is multi-tenant on **shared tables + an `org_id` column** (ADR on isolation). The rule:

> **Every tenant-scoped table carries `org_id`, and every query filters by the current
> organization. A forgotten filter is a cross-tenant data leak — the worst bug we can ship.**

We do not rely on remembering to add `WHERE org_id = ?` by hand everywhere. Enforcement options
to decide at implementation time (a follow-up ADR): a mandatory repository layer that always
injects the filter, and/or Postgres Row-Level Security (RLS) as a database-level backstop.
Isolation gets an explicit automated test: org A must never read org B's rows.

## Entities and key columns

Conventions: every table has `id` (UUID), `created_at`, `updated_at`. Tenant-scoped tables also
have `org_id`. Foreign keys end in `_id`.

### Organization
The tenant. `id`, `name`, `created_at`. The root of every ownership chain.

### User
A human who logs in. `id`, `org_id`, `email` (unique per org), `password_hash` (argon2),
`role` (owner/admin/member/viewer — see RBAC in the security baseline), `created_at`.

### ApiKey
Authenticates API calls. `id`, `org_id`, `name`, `key_prefix` (short, non-secret, shown in UI to
identify the key), `key_hash` (salted hash — we store only the hash and verify, never decrypt; see
ADR-0008 discussion: hashing suffices because we only ever *check* the key), `scopes`,
`last_used_at`, `revoked_at` (nullable — soft revoke), `created_at`. *(HSM/envelope encryption is
explicitly out of scope here; it would only matter for secrets we must decrypt later, like signing
secrets — noted in the threat model as future-if-needed.)*

### Source
An endpoint that receives third-party webhooks. `id`, `org_id`, `name`, `slug`/`ingest_path`
(the unique public URL piece given to Stripe/GitHub), `signing_secret` (for HMAC verification of
inbound webhooks — a secret we *do* need to use, so encrypt at rest), `created_at`.

### Destination
A user-owned URL to forward events to. `id`, `org_id`, `name`, `url`, `active`, `created_at`.
Org-owned and reusable across sources (ADR-0008). The outbound URL is user-supplied → SSRF
validation applies (security baseline).

### Route
Join entity: "events from this source go to this destination." `id`, `org_id`, `source_id`,
`destination_id`, `active`, `created_at`. Unique on (`source_id`, `destination_id`). Future home
for per-route filters/config.

### Event
An immutable record of a received webhook. `id`, `org_id`, `source_id`, `payload` (the body),
`headers`, `received_at`, `idempotency_key` (for dedup — see delivery-semantics ADR-0003),
`created_at`. **Never mutated** after insert — it is the source of truth for "what came in".

### Delivery
The effort to deliver one event to one destination. `id`, `org_id`, `event_id`, `destination_id`,
`status` (pending → delivering → succeeded / retrying / dead), `attempt_count`, `next_attempt_at`
(for backoff scheduling), `created_at`, `updated_at`. **This is where status lives** (ADR-0008).

### Attempt
One individual delivery try. `id`, `org_id`, `delivery_id`, `attempted_at`, `response_status`,
`response_body_snippet`, `error`, `duration_ms`. Append-only history of what happened.

## Relationships summary

- Organization 1—* User, ApiKey, Source, Destination
- Source *—* Destination, via Route (1 Route = 1 source + 1 destination)
- Source 1—* Event
- Event 1—* Delivery (one per destination it routes to)
- Destination 1—* Delivery
- Delivery 1—* Attempt

## Indexing notes (for implementation)

- Everything tenant-scoped: index/lead with `org_id`.
- `Event`: index `(org_id, source_id, received_at)` for the inspector; unique-ish on
  `(source_id, idempotency_key)` for dedup.
- `Delivery`: index `(status, next_attempt_at)` so the worker can efficiently pull "deliveries
  due for a retry now" without scanning.
- `Route`: unique `(source_id, destination_id)`.

## What is deliberately NOT here yet (scope discipline)

Payload transformation/filter rules, full-text payload search, billing/usage metering — all
deferred (problem brief out-of-scope). The model leaves room for them (e.g. Route can gain a
filter column) without needing them now.
