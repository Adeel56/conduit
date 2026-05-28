# ADR-0003: Delivery semantics — at-least-once + idempotency

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Solo builder + mentor

## Context

Conduit's core job is delivering received webhooks to user destinations. The obvious wish is
"exactly-once delivery." But exactly-once delivery over an unreliable network is **provably
impossible** in the general case: after we send a request, a missing response could mean the
destination never got it *or* got it and the acknowledgement was lost. We cannot distinguish
these, so we must choose which way to fail.

## Decision

We guarantee **at-least-once delivery** (we keep retrying until we get a success acknowledgement),
and we make duplicates *safe* by attaching a stable **idempotency key** to every delivery so
that well-behaved destinations — and our own internal dedup — can recognise and ignore repeats.

## Options considered

1. **At-most-once** (send once, never retry) — simple, but loses events on the first failure.
   Unacceptable for a reliability product. Rejected.
2. **Exactly-once** — impossible to truly guarantee end-to-end; pretending otherwise would be
   dishonest engineering. Rejected as a literal guarantee.
3. **At-least-once + idempotency (CHOSEN)** — the industry-standard, honest answer. Never lose
   an event; make duplicates harmless.

## Consequences

- **Positive:** no event loss; the trade-off is explainable and correct — being able to *say*
  "exactly-once is impossible, so we do at-least-once plus idempotency" is a senior signal.
- **Negative / trade-offs:** destinations may occasionally receive a duplicate; we mitigate
  with idempotency keys and document the contract clearly for users.
- **Follow-ups:** define the idempotency key format and the dedup window; design the retry
  schedule and dead-letter behaviour.

## Reversal

The semantics are a contract with users, so changing them later is a **breaking change** —
expensive to reverse. That is why we are deciding it carefully now, before any delivery code
exists. Internal mechanics (retry schedule, dedup window) remain cheap to tune.
