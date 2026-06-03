# Conduit — Data Model (Design)

The skeleton everything hangs off. See ADR-0008 for *why* it is shaped this way. This doc is the
*what*: entities, key columns, relationships, and how tenant isolation is enforced.

**Build status legend.** This is the target design; not all of it is built yet. Each entity is
tagged with where it stands on `main`:

- ✅ **Built** — JPA entity + Flyway migration on `main`.
- ⬜ **Planned** — designed here, no code yet.

Built so far (CON-3 → CON-12): `Organization` ✅, `ApiKey` ✅, `Source` ✅, `Event` ✅,
`Destination` ✅, `Route` ✅. Still planned: `Delivery` ⬜, `Attempt` ⬜, `User` ⬜. Where the
design below names a column or behaviour that isn't built yet (e.g. `idempotency_key` on `Event`,
`signing_secret` on `Source`, password hashing / RBAC on `User`), it is called out inline.

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

We do not rely on remembering to add `WHERE org_id = ?` by hand everywhere. The enforcement
approach actually adopted by the feature code — take `org_id` **only** from the authenticated
principal (never from a request param), scope every read with **`findByIdAndOrgId`**, and return
an **identical 404** whether a row is missing or belongs to another tenant (so cross-tenant probing
leaks nothing) — is documented in **ADR-0010** (Proposed). Postgres Row-Level Security (RLS) as a
database-level backstop remains an option recorded there. Isolation gets an explicit automated
test: org A must never read org B's rows (see the cross-tenant integration tests).

## Entities and key columns

Conventions: every table has `id` (UUID), `created_at`, `updated_at`. Tenant-scoped tables also
have `org_id`. Foreign keys end in `_id`.

### Organization ✅ *(built — V4, `Organization` entity)*
The tenant. `id`, `name`, `slug` (unique), `created_at`, `updated_at`. The root of every ownership
chain; `org_id` on every tenant table is a real FK to `organizations(id)` (CON-12 / V4).

### User ⬜ *(planned — no entity, no migration, no login path yet)*
A human who logs in. `id`, `org_id`, `email` (unique per org), `password_hash` (**Argon2 —
planned**, not implemented), `role` (owner/admin/member/viewer — **RBAC is planned**, not built),
`created_at`. Auth today is **API-key only** (see `ApiKey`); there is no human-login path yet.

### ApiKey ✅ *(built — V3, `ApiKey` entity + the live auth filter)*
Authenticates API calls. `id`, `org_id`, `name`, `key_prefix` (short, non-secret, shown in UI to
identify the key), `key_hash` (salted hash — we store only the hash and verify, never decrypt; see
ADR-0008 discussion: hashing suffices because we only ever *check* the key), `scopes`,
`last_used_at`, `revoked_at` (nullable — soft revoke), `created_at`. *(HSM/envelope encryption is
explicitly out of scope here; it would only matter for secrets we must decrypt later, like signing
secrets — noted in the threat model as future-if-needed.)*

### Source ✅ *(built — V2, `Source` entity)*
An endpoint that receives third-party webhooks. As built: `id`, `org_id`, `name`, `ingest_key`,
`active`, `created_at`, `updated_at`. The **`ingest_key`** is the unique public URL piece given to
Stripe/GitHub — a 256-bit CSPRNG secret used as the path in `POST /ingest/{ingest_key}` (unique +
indexed; not the `id`, which would be enumerable). It is stored in plaintext by design (it *is* the
URL); rotation overwrites it in place, after which the old key no longer resolves. A short,
non-secret prefix is shown in the UI; the full key is shown once at create/rotate.
**`signing_secret` is planned, not built** — inbound HMAC signature verification is a future ticket,
and the secret it needs (one we'd encrypt at rest) does not exist on `sources` yet.

### Destination ✅ *(built — V5, `Destination` entity + CRUD)*
A user-owned URL to forward events to. `id`, `org_id`, `name`, `url`, `active`, `created_at`,
`updated_at`. Org-owned and reusable across sources (ADR-0008). The outbound URL is user-supplied:
it is validated as an absolute http/https URL on write today; full **SSRF range-blocking**
(private/link-local/cloud-metadata ranges, resolve-then-validate) is a deliberately separate
follow-up ticket (security baseline), **not yet built**.

### Route ✅ *(built — V5, `Route` entity + CRUD)*
Join entity: "events from this source go to this destination." `id`, `org_id`, `source_id`,
`destination_id`, `active`, `created_at`, `updated_at`. Unique on (`source_id`, `destination_id`).
A route may only join a source and destination in the **same org** (enforced at creation — a
cross-tenant route would be an isolation hole). Future home for per-route filters/config.

### Event ✅ *(built — V2, `Event` entity)*
An immutable record of a received webhook. As built: `id`, `org_id`, `source_id`, `payload` (the
raw body, stored faithfully and never parsed), `headers`, `received_at`, `created_at`. **Never
mutated** after insert — it is the source of truth for "what came in". **`idempotency_key` is
planned, not built** — it lands with the idempotency ticket (see delivery-semantics ADR-0003);
the column is intentionally not on `events` yet.

### Delivery ⬜ *(planned — no entity, no migration yet)*
The effort to deliver one event to one destination. `id`, `org_id`, `event_id`, `destination_id`,
`status` (pending → delivering → succeeded / retrying / dead), `attempt_count`, `next_attempt_at`
(for backoff scheduling), `created_at`, `updated_at`. **This is where status lives** (ADR-0008).
Arrives with the delivery worker (a later ticket); destinations/routes exist, but nothing delivers yet.

### Attempt ⬜ *(planned — no entity, no migration yet)*
One individual delivery try. `id`, `org_id`, `delivery_id`, `attempted_at`, `response_status`,
`response_body_snippet`, `error`, `duration_ms`. Append-only history of what happened. Lands with
`Delivery`.

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
