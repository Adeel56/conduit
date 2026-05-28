# ADR-0001: Build Conduit (a webhook relay & inspector)

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Solo builder + mentor

## Context

The builder is a graduate who wants to learn — at a top-class level — how a real product is
taken from idea to production to operations, playing every role (product, eng, devops, ops).
The product must be a **vehicle** rich enough to force the full range of real engineering
concerns: a non-trivial backend, a database, caching, concurrency control, background workers,
real-time UI, multi-tenancy, security, and a complete devops pipeline.

Several "portfolio" ideas were rejected for being clones that signal nothing (task manager,
blog, e-commerce, social app). We needed a problem that is **real**, **self-contained** (no
outside domain expertise required), and **technically rich**.

## Decision

We will build **Conduit**: a self-hostable service that receives third-party webhooks,
durably stores them, lets a developer inspect them, and delivers them to user-configured
destinations with retries, backoff, dead-lettering, idempotency, and manual replay.

## Options considered

1. **Uptime/status monitor** — strong on scheduling + state machines, slightly narrower on
   concurrency and queues. Good, but less breadth.
2. **Booking engine (no double-booking)** — deepest on pure concurrency, but narrower overall
   (less devops surface, less real-time).
3. **Expiry/renewal guardian** — gentlest, but thinnest technically.
4. **Webhook relay & inspector (CHOSEN)** — maximum breadth: ingest + storage + caching +
   concurrent delivery with retry/backoff + queue/workers + real-time inspector + multi-tenancy
   + a genuinely interesting security surface (SSRF, signature verification). Engineers respect
   it because it forces idempotency and at-least-once delivery reasoning.

## Consequences

- **Positive:** every cross-cutting concern we want to learn arises *naturally* from the
  product, not artificially bolted on. The queue/worker model mirrors "tickets picked up by
  workers," reinforcing the process lesson.
- **Negative / trade-offs:** it is genuinely a multi-month effort done properly. We manage this
  by cutting scope hard (see the problem brief's out-of-scope list).
- **Follow-ups:** define the v1 scope boundary, threat model, and data model next.

## Reversal

Cheap to reverse *now* (we have no code yet). The cost of reversing rises sharply once we have
built features, so this is the right moment to commit. If the product proves wrong, the
*process* learning still transfers to any replacement vehicle.
