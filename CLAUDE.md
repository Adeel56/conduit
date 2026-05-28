# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status: inception (docs only)

There is **no application code, build file, or infrastructure yet**. The entire repo is the
`docs/` tree plus `README.md`/`CONTRIBUTING.md`. The documentation *is* the current deliverable
— this project is a deliberate simulation of a full product lifecycle (inception → design →
build → harden → launch → operate), and the recorded *decisions and their reasoning* matter as
much as the eventual code. Do not treat the docs as scaffolding to skip past.

There are therefore no build/test/lint commands yet. They arrive in the **Foundation** stage
(Maven or Gradle for the Spring Boot app; a per-project `docker-compose.yml` for services).
**Update the "Commands" section below when that tooling lands** — do not invent commands now.

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
  bolted on after. The full **Definition of Done** is a hard gate (checklist at the end of this file).
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
- **Argon2** password hashing; scoped, hashed-at-rest, rotatable API keys; app RBAC (owner/admin/member/viewer).
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

## Definition of Done (every change — from `CONTRIBUTING.md`)

- [ ] Acceptance criteria met, including any **security** criteria on the ticket.
- [ ] Tests written and passing.
- [ ] Reverse plan filled in and credible, matched to the change type.
- [ ] Observability added (logs/metrics for the new behaviour).
- [ ] Docs/ADR updated if a decision was made.
- [ ] CI green, including security scans (Trivy/CodeQL).
- [ ] You can explain every line that merges (`docs/adr/0007`).

## Commands

None yet (inception phase — no build tooling exists). Populate when Foundation adds the Spring
Boot build and `docker-compose.yml`, including how to run a single test.
