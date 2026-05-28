# ADR-0008: Data model — normalized, with Route and Delivery as first-class entities

- **Status:** Accepted
- **Date:** 2026-05-26
- **Deciders:** Builder (designed first pass + chose normalization) + mentor (review)

## Context

We designed Conduit's data model in a whiteboard session. The builder correctly identified the
six core entities (Organization, User, API Key, Source, Destination, Event, Attempt) and the
three delivery states. Two relationships turned out to be subtler than a first pass suggests, and
both have a simpler alternative — so the choice is a real decision, not a clear-cut correctness
issue.

## Decision

Use the **fully normalized** model:

- `Organization` is the tenant; everything carries `org_id` (see ADR on tenant isolation).
- `Organization` has many `User`, `API Key`, `Source`, `Destination`.
- **`Source` ↔ `Destination` is many-to-many**, resolved by a **`Route`** join entity (a source
  can fan out to many destinations; a destination can receive from many sources). Destinations
  are **org-owned and reusable**, not owned by a single source. `Route` is also the natural home
  for future per-route settings (active flag, filters).
- An `Event` is received at a `Source` and stored **immutably** (what came in).
- For each `Route` from that source, a **`Delivery`** is created — the effort to get **one event
  to one destination**. **`Delivery` holds the status** (e.g. pending → delivering → succeeded /
  retrying / dead) and the retry count.
- A `Delivery` has many **`Attempt`** rows — one per individual try (timestamp, response code,
  error).

## Why status lives on Delivery, not Event (the load-bearing reason)

When one event fans out to multiple destinations, each destination can be in a *different* state
at the same time: succeeded at A, retrying (attempt 4) at B, dead at C. The event therefore has
**no single status** — status is a property of *(event, destination)*, which is exactly what
`Delivery` represents. A `status`/`tries` column on `Event` cannot express concurrent
per-destination states, and fan-out is a core Conduit feature.

## Options considered

1. **Status + tries columns on `Event`, plus `Attempt` rows (simpler, fewer joins)** — works
   until an event goes to more than one destination, then breaks (no single status/try-count).
   Rejected: fan-out is core; the builder's own Stripe example (→ payment processor + bank
   record) already requires per-destination state.
2. **`Destination` belongs to one `Source` (foreign key, simpler)** — fine and legitimate; trades
   reuse/flexibility for simplicity and accepts redundancy when two sources need the same URL.
   Rejected because routing is the heart of the product and will be expanded.
3. **Normalized: Route join + Delivery entity (CHOSEN)** — more tables and joins, but correctly
   represents reality and won't constrain future expansion (filters, per-route config, reusable
   destinations).

## Consequences

- **Positive:** the model can represent concurrent per-destination delivery state; destinations
  are reusable; routing is first-class and extensible; no data loss/ambiguity under fan-out.
- **Negative / trade-offs:** more entities and more joins than the simplest model. Mitigated by
  indexes and by querying `Delivery`/`Attempt` directly for operational views ("all retrying
  deliveries") without always joining `Event`.
- **Follow-ups:** define columns, keys, indexes, and the tenant-isolation enforcement in the
  data-model design doc; the `Delivery` status values feed the delivery-engine state machine.

## Reversal

The schema is created via reversible migrations (every change has a tested down-migration; see the
reversibility ADR/template). Collapsing back toward the simpler model later would be a data
migration, not a code revert — moderately expensive — which is itself a reason to choose the more
flexible shape now, while it is free.
