# ADR-0007: AI-assisted development working model

- **Status:** Accepted
- **Date:** 2026-05-26
- **Deciders:** Builder + mentor

## Context

The builder wants to use AI to develop ~10x faster while genuinely *understanding* everything
built (the stated core goal). Three "hands" are available: the mentor (architecture/guidance,
cannot touch the builder's machine), Claude Code / CLI inside the WSL project (can read files
and run commands on the real disk), and the builder (decides, reviews, learns, and owns the work).
We need an explicit working model so speed never comes at the cost of understanding.

## Decision

Adopt a fixed flow for every change: **mentor → builder → Claude Code → builder → mentor.**

1. **Mentor writes a task spec** (effectively a ticket): what to do, why, and the constraints —
   not the code itself.
2. **Builder relays it to Claude Code in their own words** — the act of re-specifying forces
   comprehension; vague direction produces bad output.
3. **Claude Code executes** on the real disk (writes code, runs commands).
4. **Builder reviews the output and must be able to explain every line.** If not, they
   interrogate Claude Code or return to the mentor before proceeding.
5. **Builder runs it through the enforced workflow** (branch → commit → PR → self-review → merge).
6. **Builder reports the outcome;** mentor reviews *understanding* and the decision is documented.

Primary tool: **VS Code + Claude Code extension** (diffs are visible = learning), with the CLI
for commands. Cowork deferred to later, repetitive ops once the mechanics are understood.

## Options considered

1. **Mentor or AI writes code directly, builder accepts it** — fastest, but defeats the
   understanding goal; builder becomes a rubber stamp. Rejected.
2. **Builder writes everything by hand, no AI** — maximal understanding, but slow and not the
   skill being targeted (directing + verifying AI is increasingly the real job). Rejected.
3. **Mentor specs → builder directs AI → builder verifies (CHOSEN)** — AI provides speed; the
   builder is the deliberate bottleneck for comprehension; nothing merges unexplained.

## The org analogy (why this is realistic)

- Mentor ≈ **tech lead / architect** — writes tickets, reviews design and understanding.
- Claude Code ≈ **fast junior dev** — implements from a well-specified ticket.
- Builder ≈ **the accountable engineer** — translates intent into precise specs, critically
  reviews output, owns what ships. Operating this middle role *is* the senior skill.

## Consequences

- **Positive:** real speed with enforced understanding; mirrors a modern AI-assisted team;
  every change is reviewed and explainable.
- **Negative / trade-offs:** slower than letting AI run free — deliberately. The friction is the
  learning. Requires builder discipline to not merge things they can't explain.
- **Follow-ups:** the "can I explain every line?" check is part of Definition of Done already in
  CONTRIBUTING; this ADR makes the surrounding flow explicit.

## Reversal

Trivially reversible — it is a process choice, tunable at any time (e.g. more autonomy to AI on
boilerplate once trust is established, more hands-on for novel/hard parts).
