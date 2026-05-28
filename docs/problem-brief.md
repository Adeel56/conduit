# Conduit — Problem Brief (Inception v0.1)

**One-liner:** A self-hostable service that reliably receives third-party webhooks, lets you
inspect them, and delivers them to your own endpoints with retries and replay.

## The problem
Developers integrating services like Stripe, GitHub, or Shopify receive events via webhooks.
In practice this is painful: you can't easily receive them on localhost, you can't see what was
actually sent, and when your handler fails the event is often lost (provider retry policies vary
wildly). You can't replay a failed event, and you can't fan one event out to several internal
services. "Did the webhook arrive, and what was in it?" eats hours.

## Today's workarounds, and why they fall short
- **ngrok + print statements** — no persistence, no replay.
- **RequestBin** — ephemeral, can't forward.
- **Roll-your-own receiver** — reinventing retries/dedup, usually badly.
- **Paid SaaS (Hookdeck/Svix)** — costs money, not self-hostable.

## Primary user
A backend dev at a small startup wiring up 2–3 webhook providers, who needs reliable receipt,
visibility, and replay — in development *and* production.

## What success looks like (outcomes, not features)
- Every received event is durably stored and visible.
- Failed deliveries are retried with backoff and can be replayed by hand.
- An event is never delivered to a destination more than once *unintentionally*.
- A dev can inspect any event's payload, headers, and full delivery history live.

## In scope — the tight v1 core
Per-source ingest URLs that durably store incoming webhooks; optional signature verification;
fan-out to one or more destinations; a delivery worker with a queue, concurrency,
exponential-backoff retries, and dead-lettering; idempotency/dedup; an inspector UI (list
events, view payload, see attempts, manual replay); multi-tenancy (orgs, scoped API keys);
auth.

## Explicitly OUT of scope (deferred — this is the deliberate scope cut)
Payload transformation/filtering rules engine; usage metering/billing; full-text payload
search; multi-region HA; client SDKs; fancy analytics. Named so we *consciously* don't build
them.

## The honest hard parts (what makes it real)
- Exactly-once delivery is impossible over a network → we target **at-least-once + idempotency**
  (see ADR-0003), and explaining that trade-off is itself a senior signal.
- Securing a public ingest endpoint against abuse.
- SSRF risk on user-supplied outbound destinations.
- Handling large payloads safely.

## Non-functional targets (to be hardened into numbers during Design)
- No webhook delivered more than once *unintentionally*.
- p99 ingest latency target (e.g. < 200 ms) — to be set and tested.
- Graceful degradation under overload (load shedding), not collapse.
