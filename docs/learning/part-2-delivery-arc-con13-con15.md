# Conduit — Concepts Study Guide (Part 2: the Delivery Arc, CON-13 → CON-15)

*The concurrency centerpiece and the two security/correctness features around it. These are the
highest-value interview topics in the whole project — read this even if you skim the code.*

---

## 1. At-least-once delivery, and why exactly-once is a myth — **highest interview value**

**The claim to internalize:** you cannot guarantee exactly-once delivery over an unreliable network.
If Conduit POSTs to a destination and the connection drops *after* the destination processed it but
*before* the ack comes back, Conduit can't tell "succeeded" from "failed" — so it must either risk
never retrying (at-most-once, can lose data) or risk a duplicate (at-least-once, safe but may
repeat). Reliable systems choose **at-least-once** and make duplicates harmless.

**The pattern:** *at-least-once delivery + idempotent processing.* The sender guarantees it'll
deliver at least once (retrying on uncertainty); the receiver de-duplicates so a repeat is a no-op.
That's how Stripe, AWS SQS, Kafka, every serious queue works.

**Interview phrasing:** "Exactly-once is impossible over a network because the ack can be lost, so I
do at-least-once with retries and make repeats safe — at-least-once delivery plus idempotent
consumption is the standard reliability contract."

---

## 2. Why status lives on Delivery, not Event (the fan-out insight)

One Event can route to many destinations. Destination A might succeed while B fails and C is still
retrying. So "delivery status" isn't a property of the event — it's per (event, destination). Hence:

- **Event** — immutable, "what came in." No status.
- **Delivery** — one row per (event, route). Carries `status` (pending → in_flight → delivered /
  failed) and `attempt_count`. This is the unit of work.
- **Attempt** — one row per individual try (timestamp, response code, error, duration). The audit
  trail.

**Interview phrasing:** "Because one event fans out to many destinations with independent outcomes,
status belongs on a per-destination Delivery row, with an Attempt row per try for observability."

---

## 3. The worker claim — the concurrency crux — **high value, this is the hard part**

Multiple workers (and later, multiple app instances) pull pending deliveries from the same table.
The danger: two workers grab the *same* delivery and both POST it → duplicate. You must ensure each
pending delivery is claimed by **exactly one** worker at a time.

Two standard mechanisms:

1. **`SELECT ... FOR UPDATE SKIP LOCKED`** (Postgres): a worker selects due rows *and locks them*;
   `SKIP LOCKED` means other workers skip already-locked rows instead of blocking. Each worker gets a
   disjoint batch. This is the idiomatic Postgres job-queue technique.
2. **Guarded status transition:** `UPDATE deliveries SET status='in_flight' WHERE id=? AND
   status='pending'` — the `AND status='pending'` means only one update can win; if it affected 0
   rows, another worker already claimed it.

Either way, the claim is *atomic*, so no double-delivery. This is the single most important thing to
understand in CON-13, and a classic system-design interview question.

**Interview phrasing:** "Workers claim jobs with `SELECT FOR UPDATE SKIP LOCKED` (or a guarded
status update), so each pending delivery is processed by exactly one worker — that's what prevents
double-delivery under concurrency."

---

## 4. Exponential backoff with jitter

When a destination fails, don't retry immediately or on a fixed interval — back off exponentially
(1s, 4s, 16s, … up to a cap) so a struggling destination gets breathing room, and add **jitter**
(randomness) so many failed deliveries don't all retry at the same instant (the "thundering herd").
After a max number of attempts, mark the delivery `failed` — a **dead letter** you can inspect and
replay later rather than retrying forever.

**Interview phrasing:** "Retries use capped exponential backoff with jitter to avoid hammering a
failing endpoint and to spread retry load; after a cap they dead-letter for manual replay."

---

## 5. Virtual threads — why they fit this problem (CON-13, ADR-0004)

Delivery is **blocking I/O**: a worker POSTs and waits for the destination's response. With classic
OS threads, handling thousands of concurrent in-flight deliveries means thousands of expensive
threads (or a complex async/reactive rewrite). **Virtual threads** (Java 21) are cheap — millions can
exist — and let you write simple, blocking, synchronous code that still scales to massive I/O
concurrency. You get reactive-level throughput with imperative-level simplicity.

**Interview phrasing:** "Delivery is blocking HTTP I/O, so virtual threads let me write straight-line
blocking code that still scales to thousands of concurrent deliveries, without going reactive."

---

## 6. Timeouts everywhere — a hung destination must not hang a worker

Every outbound call needs a **connect timeout** and a **read timeout**. Without them, one
unresponsive destination ties up a worker indefinitely, and enough of them starve the whole pool.
Also bound the response read (a destination could stream gigabytes). Timeouts turn "hang forever"
into "fail fast → retry later," which the backoff machinery then handles.

**Interview phrasing:** "Every outbound call has connect+read timeouts and a bounded response read,
so a slow or malicious destination fails fast into the retry path instead of starving workers."

---

## 7. Keep delivery OUT of the ingest request path

This ties back to the 202 decision (Part 1 §2). Fan-out (creating the pending Deliveries) and the
actual POSTing happen **after** the ingest request returns — via an after-commit hook and a
background worker, never inside the 202 handler. If you let delivery leak into the request path, a
slow destination would slow down *ingest*, which is exactly what the whole async design exists to
prevent.

**Interview phrasing:** "Ingest persists and returns 202; fan-out and delivery happen asynchronously
post-commit, so downstream slowness never backpressures ingestion."

---

## 8. HMAC signature verification (CON-14) — proving authenticity

A secret URL proves *routing*, not *authenticity* — anyone who learns the URL can forge a webhook.
HMAC fixes that: provider and Conduit share a secret; the provider sends
`signature = HMAC(secret, raw_body)` in a header; Conduit recomputes it over the **raw bytes** and
**constant-time compares**. No secret → no valid signature → forgery rejected. Two subtleties:
- **Over raw bytes, before parsing** — re-serializing the body would change it and break the MAC.
- **Constant-time compare** — a byte-by-byte early-exit comparison leaks timing info; use a
  constant-time equals (same reason as API-key verification in Part 1 §6).

**Interview phrasing:** "I verify an HMAC over the exact raw request bytes with a constant-time
comparison, so a forged webhook without the shared secret is rejected before it's ever parsed."

---

## 9. Idempotency / dedup (CON-15) — the receiver half of at-least-once

Since senders retry, the same webhook arrives more than once. Dedup makes a repeat a no-op: resolve a
per-source idempotency key (a provider-supplied id header, or a hash of the body), and if an Event
with that `(source_id, key)` already exists, return the *existing* event's response instead of
storing a duplicate or re-fanning-out. The real guarantee is a `UNIQUE(source_id, idempotency_key)`
constraint; a pre-check is the friendly path and the constraint backstops the concurrent-duplicate
race (using `saveAndFlush` so the violation actually surfaces — the lesson from the CON-10 fix).

**Interview phrasing:** "I dedupe on a per-source idempotency key backed by a unique constraint, so a
retried webhook maps to the same event and never double-processes — the idempotent-consumer half of
at-least-once."

---

## 10. The big picture to be able to draw on a whiteboard

```
 Provider --POST signed webhook--> [Ingest]  --verify HMAC (CON-14)--> --dedup (CON-15)-->
   store immutable Event, return 202 (fast)            |
                                                       | (after commit, async)
                                                       v
                                          fan out -> pending Deliveries (one per route)
                                                       |
                                          [Workers] claim (SKIP LOCKED), POST to destination,
                                          record Attempt, success -> delivered,
                                          failure -> backoff + retry -> ... -> dead-letter
```

If you can draw and narrate that diagram — fast 202 ingest, async fan-out, exclusive worker claim,
backoff retries, at-least-once + idempotency, virtual threads for I/O concurrency — you can hold your
own in a backend system-design interview. That's the payoff of this arc.

---

*Next (after this arc merges): the Deploy stage — containerization you already have, plus
Kubernetes/kind and infra-as-code — and then Operate (incident + postmortem) and Learn (retro).*
