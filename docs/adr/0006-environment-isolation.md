# ADR-0006: Environment isolation & reproducibility strategy

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Builder (raised the concern) + mentor

## Context

The builder asked, unprompted, how to stop one project's configuration from disturbing another's.
This is the core concept of **environment isolation / reproducibility**, and it is the same
principle that underlies containers and orchestration: *a unit of software should carry
everything it needs and be unable to interfere with — or be interfered by — anything outside it.*

## Decision

Isolate every project at **four layers**:

1. **Dependencies (per project)** — Maven/Gradle pin exact dependency versions in the project's
   build file, resolved per project. One project's library versions cannot affect another's.
2. **Runtime version (per project)** — manage the JDK via **SDKMAN!**, installing the **Eclipse
   Temurin** distribution (we install the *Temurin distribution* via the *SDKMAN! version
   manager* — distribution and manager are different things). This lets each project pin its own
   JDK version with no global conflict.
3. **Services (per project)** — Postgres, Redis, etc. are **never installed on the host**. Each
   project runs its own as **Docker containers** via a per-project `docker-compose.yml`, on their
   own ports, removable cleanly with `docker compose down`.
4. **Config (per project, 12-factor)** — configuration and secrets live in **environment
   variables / per-project `.env` files**, never hardcoded, never shared. Same image runs in
   dev/staging/prod with only config swapped. (`.env*` is already git-ignored.)

## Options considered

- **Global installs of Java + databases** — simplest, but guarantees version conflicts across
  projects ("installed Postgres for A, broke B"). Rejected.
- **Four-layer isolation (CHOSEN)** — the professional standard; each project fully
  self-contained.

## Consequences

- **Positive:** Conduit is self-contained and reproducible; cannot disturb or be disturbed by
  other projects; mirrors how real organizations work and previews the container/orchestration
  model.
- **Negative / trade-offs:** slightly more upfront setup (SDKMAN!, compose files) than a global
  install — paid back immediately in avoided conflicts.
- **Follow-ups:** Foundation stage adds `docker-compose.yml` (services) and `.env.example`
  (config contract) to the repo.

## Reversal

Per-layer and cheap to reverse. The principle scales forward: the same isolation thinking becomes
Docker images, then Kubernetes pods, then separate cloud environments.
