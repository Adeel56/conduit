# ADR-0009 — Parallel feature development with multiple AI agents

- **Status:** Accepted
- **Date:** 2026-06-04
- **Deciders:** Adeel (sole engineer)
- **Context tickets:** CON-10, CON-11, CON-12 onward

## Context

Until now, tickets have been implemented strictly one at a time (one branch, one PR, merge, repeat).
That is the safest default and produced a clean linear history. But several upcoming features are
genuinely independent, and the tooling (Claude Code, Opus 4.8) can run multiple agents
concurrently. We want to move faster **without** abandoning the discipline that has made the project
good (short-lived branches, required CI checks, reviewed PRs, reversible changes).

The temptation is to fan out many agents and merge everything quickly. The risk is that naive
parallelism creates merge conflicts, inconsistent decisions, and — worst — *semantic* collisions
that don't show up as file conflicts (two features built on incompatible assumptions about the same
data model).

## Decision

We adopt **wave-based parallel development**:

1. **Work is grouped into waves.** Within a wave, tickets are *independent* and run as parallel
   agents on separate branches. Between waves, work is *sequential* because of dependencies.

2. **"Independent" is judged by shared assumptions, not just shared files.** Two tickets that never
   touch the same file can still collide if they depend on the same evolving contract (e.g. the org
   model, a shared DTO, the security config). Such tickets are sequenced, not parallelized.

3. **Shared resources are pre-assigned to prevent collisions.** Before a parallel wave starts, the
   tech-lead (planning) step assigns:
   - **Flyway migration version numbers** (e.g. CON-A → V4, CON-B → V5) so two agents never both
     create `V4`. Migrations are append-only and ordered; a collision is a hard conflict.
   - **Package/module ownership** where overlap is possible.
   - Any shared config touch points (ideally none per wave; if unavoidable, that ticket goes solo).

4. **The harness is never weakened to go faster.** Required CI checks (build+test, Testcontainers
   integration, Trivy, CodeQL) stay required. Speed comes from (a) parallel branches whose CI runs
   concurrently, and (b) front-loaded planning so agents execute rather than design — never from
   removing checks.

5. **Foundational data-model changes go first and alone.** A change that everything else references
   (e.g. the `organizations` table) is sequenced ahead of the features that build on it, so the
   parallel wave builds on a stable contract.

6. **Tickets are written just-in-time, one wave ahead.** Tickets for a wave are written after the
   previous wave merges, so they describe the actual codebase, not an imagined future one. Writing
   the whole backlog up front would produce tickets that rot as the code beneath them changes.

## Consequences

**Positive:** real throughput gains on independent work; the git history still reads as discrete,
reviewed changes; documents stay consistent because planning is centralized per wave; the engineer
learns *which* work parallelizes and *how* to coordinate it — a genuine senior skill.

**Negative / costs:** requires a planning step before each wave (assigning migrations/ownership);
the linear-history simplicity is slightly reduced (concurrent branches); a wave's slowest PR gates
the wave. These are acceptable and mirror how real teams operate.

**Reversal:** drop back to strictly one-ticket-at-a-time. No code impact — this is a process ADR.

## Notes

This ADR governs process only. It does not change any technical decision in ADRs 0001–0008.
The first foundational-first application is CON-12 (organizations) going solo before the CON-10 /
CON-11 CRUD wave parallelizes on top of it.
