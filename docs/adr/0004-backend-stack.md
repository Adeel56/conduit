# ADR-0004: Backend stack — Spring Boot on Java 21 (virtual threads)

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Builder (deferred to mentor's recommendation)

## Context

Conduit is, at its core, an engine that makes large numbers of outbound HTTP calls and waits on
them (delivering webhooks to user destinations, with retries). The dominant technical concern is
therefore **I/O concurrency**: how to wait on thousands of slow network calls without exhausting
the server.

The builder is fluent in Node/Express (single-threaded event loop) and knows Java/OOP/DSA but is
newer to Spring. The stated project goal is **conceptual understanding and "how it works under
the hood,"** with AI used for syntax — *not* shipping as fast as possible.

## Decision

Build the backend in **Spring Boot on Java 21**, using **virtual threads** for the concurrent
delivery workload.

## Options considered

1. **Node + TypeScript** — builder already fluent (fastest path); event loop is excellent at
   I/O waiting. *Rejected as primary* because (a) it's the path of least *growth*, and (b) the
   concurrency machinery is hidden by the event loop, working against the "under the hood" goal.
2. **Java + Spring, classic OS threads** — readable blocking code, but heavy threads cap
   concurrency for this exact workload. *Rejected* in favour of virtual threads.
3. **Java 21 + Spring + virtual threads (CHOSEN)** — write simple, synchronous, readable
   blocking code, while the JVM multiplexes millions of virtual threads onto a small carrier
   pool. Node-like scalability with debuggable synchronous code, and the concurrency mechanics
   (mounting/unmounting, carrier threads, blocking no longer pinning an OS thread) are
   **visible and inspectable** — directly serving the learning goal.

## Why the mentor chose this (the reasoning, made explicit)

- The goal is *understanding*, and this stack makes concurrency observable rather than hidden.
- It stretches the builder beyond what they already know (Node), which is the point of a growth
  project.
- Spring Boot + a reliability-oriented system is a strong, in-demand backend signal.
- Mature ecosystem for everything else we need: JPA/Hibernate, Flyway (reversible migrations),
  Resilience4j (circuit breakers/retries), Micrometer (observability).
- The cost — a learning curve — is exactly the cost we *want* to pay, and AI covers syntax.

## Consequences

- **Positive:** maximal learning on the hardest part (concurrency); strong resume signal;
  batteries-included ecosystem for migrations, resilience, and metrics.
- **Negative / trade-offs:** slower initial velocity than Node for this builder; Spring's
  "magic" (auto-configuration, dependency injection) must be understood, not just trusted —
  we will deliberately open the hood on it rather than treat it as a black box.
- **Follow-ups:** Foundation stage initializes a Spring Boot 3.x / Java 21 project; we verify
  virtual threads are enabled and observe their behaviour during the delivery-engine sprint.

## Reversal

Moderately expensive once code exists (it's the core language). Cheap *now*, before any code —
which is why we commit at this moment. The *architecture* (queue + workers + Postgres + Redis)
is language-agnostic, so the design work transfers even if the stack were ever reconsidered.
