# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status: active build (Build stage)

This is a **running Spring Boot 3.5.14 / Java 21 application**, not a docs-only repo. The Build
stage is well underway: tickets **CON-3 through CON-12** are merged on `main`. What exists today:

- **Flyway migrations V1–V5**: V1 baseline (no schema, proves the pipeline), V2 sources + events,
  V3 api_keys, V4 organizations (makes `org_id` a real FK), V5 destinations + routes. Each schema
  migration has a **tested undo** in `src/main/resources/db/undo` (U2–U5; V1 has no schema, so no
  undo). Flyway Community runs no undos automatically — they are the tested reverse path.
- **Stateless API-key auth** (Spring Security): every request needs a valid key except a public
  allowlist (`/health`, `/ingest/**`). See `src/main/java/dev/conduit/auth/SecurityConfig.java`.
- **Features shipped:** ingest (`POST /ingest/{ingestKey}` stores an immutable Event), the event
  inspector (list/view, tenant-scoped), organizations, destinations + routes, source CRUD.
- **Testcontainers integration tests** (boot the app against a real Postgres), a **multi-stage
  distroless Dockerfile** (non-root, pinned by digest), and **GitHub Actions CI** with a Trivy
  image scan + CodeQL static analysis.

The project remains a deliberate simulation of a full product lifecycle (inception → design →
build → harden → launch → operate); the recorded *decisions and their reasoning* still matter as
much as the code. The design-intent sections below (tech stack, security model, data model) record
where this is **heading** — some of it is built, some is still planned; the data model
(`docs/data-model.md`) marks each entity built vs. unbuilt.

## What Conduit is

A self-hostable service that **receives** third-party webhooks (Stripe/GitHub/etc.), durably
**stores** them, lets a developer **inspect** them, and **delivers** them to user-configured
destinations with retries, backoff, dead-lettering, idempotency, and manual replay. See
`docs/problem-brief.md` for scope (and the explicit out-of-scope cut).

## Who you are working with

A graduate engineer using this project for top-class, full-lifecycle experience. They use AI to
move fast **but must understand everything that ships** — your job is to implement well-specified
tasks and explain your work, never to decide silently or hand them code they can't justify. The
working model (`docs/adr/0007`) is **mentor specs the task → builder re-specifies it to you →
you implement → builder reviews and must be able to explain every line → builder runs the
workflow**. You are the fast junior dev in that loop; the builder is accountable for what merges.

## Non-negotiable working rules (these are enforced — read before doing anything)

Full detail in `CONTRIBUTING.md` and the ADRs. The load-bearing constraints:

- **Never commit to `main`.** `main` is always releasable. Every change is a short-lived branch:
  `feat/<ticket-id>-<slug>`, `fix/<ticket-id>-<slug>`, or `chore/<ticket-id>-<slug>`.
- **Ticket first, then branch.** No code without a ticket. The PR description **is** the filled-in
  change & reversal template (`docs/templates/change-and-reversal.md`) — every change states how
  to *undo* it, matched to its type (code revert vs. down-migration vs. feature-flag vs. config).
- **Conventional Commits** (`feat:`/`fix:`/`chore:`/`docs:`/`refactor:`/`test:`); squash-merge.
- **Never add an AI co-author to commits.** No `Co-Authored-By: Claude …` (or any AI/agent)
  trailer — and no AI mention in commit messages. Commits are authored solely by the accountable
  engineer (`docs/adr/0007`); AI assists, it does not co-sign the history.
- **Significant decisions get an ADR** in `docs/adr/` (next number, `0000-template.md` shape),
  including the options **rejected** and a **reversal** plan — that section is the point of an ADR.
- **Security is shifted left** — security criteria ride on the ticket as acceptance criteria, not
  bolted on after. The full **Definition of Done** (the canonical checklist in `CONTRIBUTING.md`)
  is a hard gate on every change.
- **You must be able to explain every line that merges.** AI accelerates the *typing*, not the
  *deciding* (`docs/adr/0007`). **Verify any AI-suggested dependency against the official registry
  before adding it** (hallucination → dependency-confusion is an in-scope threat).
- **Develop inside WSL**, with the repo on the Linux home filesystem — **never under `/mnt/c`**
  (`docs/adr/0005`). Ensure `which git` resolves to `/usr/bin/git`, not a Windows binary.

## Tech stack (decided in ADRs, not yet built)

- **Backend: Spring Boot 3.x on Java 21 with virtual threads** (`docs/adr/0004`). The core
  workload is massive outbound HTTP I/O (delivering webhooks with retries); virtual threads let
  delivery code stay simple/synchronous/blocking while scaling. Chosen for *understanding* the
  concurrency mechanics, not for fastest delivery — keep the "under the hood" goal in mind.
- **Ecosystem:** JPA/Hibernate, **Flyway** for reversible migrations (every change needs a tested
  down-migration or expand/contract), **Resilience4j** (circuit breakers/retries), **Micrometer**
  (observability is built-in, not bolted on).
- **Stateful services:** Postgres + Redis. A **job queue + workers** drives delivery (concurrency,
  exponential-backoff retries, dead-lettering); Redis also backs rate limiting (sliding window).
- **Local-first infra** (`docs/adr/0002`): everything self-hosted locally with the real toolchain
  — `kind` (real Kubernetes), LocalStack (S3 API), Terraform — for fearless free practice, then
  **one real-cloud capstone deploy** at the end. The build-vs-buy register
  (`docs/decisions/build-vs-buy.md`) records which local components become managed services in cloud.
- **Per-project isolation, four layers** (`docs/adr/0006`): pinned deps; JDK via **SDKMAN!**
  (Eclipse **Temurin** distribution); services as **Docker containers**, never host-installed;
  config in env vars / per-project `.env` (12-factor, `.env*` is git-ignored).

## Data model (`docs/adr/0008`) — fully normalized

`Organization` is the tenant; everything carries `org_id`. Org has many `User`, `ApiKey`,
`Source`, `Destination`. **`Source` ↔ `Destination` is many-to-many via a `Route` join entity**
(destinations are org-owned and reusable; Route is the home for per-route settings). An `Event`
is received at a `Source` and stored **immutably**. For each Route, a **`Delivery`** is created —
the effort to get *one event to one destination* — and **status lives on `Delivery`, not `Event`**
(under fan-out, one event can be succeeded at A, retrying at B, dead at C — there is no single
event status). A `Delivery` has many **`Attempt`** rows (one per try).

## Delivery semantics (`docs/adr/0003`) — do not violate the contract

Exactly-once delivery over a network is impossible. Conduit guarantees **at-least-once + a stable
idempotency key** so duplicates are *safe*. Never claim or design for exactly-once; this is a
user-facing contract and changing it later is a breaking change.

## Security model that must shape the code (`docs/security/`)

A threat model has a **boundary** — defend what's ours, consciously delegate the rest. Controls
that must land in the relevant code/manifests:

- **Outbound SSRF protection** (the signature risk): destinations are user-supplied URLs — block
  private/link-local/cloud-metadata ranges and **resolve-then-validate** to defeat DNS rebinding.
- **Tenant isolation at the row level**, tested explicitly (a cross-org leak is the worst-case bug).
- **Inbound HMAC signature verification**, replay-window checks, payload size caps / decompression-bomb guards.
- **API-key auth (built today):** a stateless Spring Security filter, no sessions. A presented key
  is `prefix.secret`; we look up by the non-secret `prefix`, then verify the secret against a
  **salted SHA-256** hash stored as `salt:hash` with a **constant-time** compare. SHA-256 (not
  Argon2) is deliberate here — the secret is already 256 bits of CSPRNG entropy, so a fast salted
  hash is cryptographically sufficient (see `apikey/ApiKeyHasher.java`). Keys are scoped
  (`scopes` column), **hashed-at-rest** (raw key never stored), and **revocable** (soft `revoked_at`;
  revoked keys fail auth). Every failure mode returns an identical 401 (existence/revocation never
  leaked), and a dummy-hash comparison equalizes timing on the not-found path
  (`apikey/ApiKeyService.java`).
- **Planned (NOT built yet):** **Argon2** password hashing, a **`User`** entity, and app **RBAC**
  (owner/admin/member/viewer). There is no human-login path today — auth is API-key only. The
  data-model entries for these are design intent; do not assume they exist in code.
- App-layer rate limiting + **load shedding** (fast 429), circuit breakers, bulkheads; volumetric DDoS is **delegated to the edge** (Cloudflare).
- **Containers** are reviewed against `docs/security/container-baseline.md` as a gate: multi-stage
  build, distroless/pinned-by-digest base, non-root, read-only rootfs, drop ALL caps,
  `runAsNonRoot`, resource limits + probes.

## Where things live

- `docs/adr/` — architecture decisions (what & why, with rejected options + reversal); use `0000-template.md` for new ones.
- `docs/security/` — threat model, security baseline, container baseline.
- `docs/decisions/` — build-vs-buy register.
- `docs/templates/` — the change & reversal template (fill in on every ticket/PR).
- `docs/problem-brief.md` — scope, primary user, and the explicit out-of-scope cut.
- `docs/runbooks/` — operational procedures (deploy/rollback/recover). *Planned; not created yet.*
- `README.md` — the product arc and why the repo looks this way; `CONTRIBUTING.md` — branching model, PR lifecycle, Definition of Done.

## Definition of Done

The canonical Definition of Done checklist lives in **`CONTRIBUTING.md`** — every change must meet
it. (It is kept in one place on purpose; do not copy it back here.)

## Commands

Maven (via the committed wrapper `./mvnw`) is the build tool. The integration tests use
Testcontainers, so **Docker must be running** for `verify` and for any single integration test.

```bash
# Full build + all tests (unit + Testcontainers integration tests). Requires Docker.
./mvnw verify

# Run a single test class…
./mvnw -Dtest=EventInspectorIntegrationTest test
# …or a single method:
./mvnw -Dtest=EventInspectorIntegrationTest#sourceIdFilterStaysOrgScoped test
# (Integration tests are *Test classes run by Surefire — there is no separate Failsafe phase —
#  so they still need Docker even under `test`.)

# Bring up the whole stack in containers: Postgres + a Redis stub (placeholder for the future
# rate-limiter / delivery queue; nothing talks to it yet) + the app on :8080. Requires a .env:
cp .env.example .env          # then set POSTGRES_PASSWORD
docker compose up --build
curl localhost:8080/health    # -> {"status":"UP"} (200; 503 if the DB is down)
docker compose down           # add -v to also wipe the Postgres volume

# Run the app from source against just the DB in a container (reads SPRING_DATASOURCE_* from env):
docker compose up -d postgres
./mvnw spring-boot:run
```
