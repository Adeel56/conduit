# CON-<ID> — <short title>

**Type:** feat | fix | chore | docs/process | refactor | test · **Branch:** `<type>/CON-<ID>-<slug>`
· **Status:** Proposed | In progress | In review | Merged | Superseded · _(Migration: V<N> — only if this ticket owns a schema change)_

> Copy this file to `docs/tickets/CON-<ID>-<slug>.md` and fill it in. Keep the **Status** line
> current as the ticket moves — it is the single source of truth for where the ticket stands, so
> state is *tracked*, not guessed from branch/PR existence. The PR description is the filled-in
> [change & reversal template](change-and-reversal.md); the
> [`.github/PULL_REQUEST_TEMPLATE.md`](../../.github/PULL_REQUEST_TEMPLATE.md) auto-loads it.

## Goal (value)

What user/system value does this deliver, and why now? One short paragraph — the "so what".

## Key design decisions (the "why")

- The load-bearing choices and their rationale. Note any **rejected** option worth recording.
- If a decision is significant enough, it gets its own ADR (`docs/adr/`) — link it here.

## Acceptance criteria

- [ ] The concrete, checkable outcomes that mean "done".
- [ ] Tests written and passing (name the critical one, e.g. a tenant-isolation test).
- [ ] `./mvnw verify` green; Trivy/CodeQL gates green (where applicable).

## Security criteria (shifted left)

- [ ] The security requirements ride here as acceptance criteria, not bolted on after.
- [ ] If none apply, say so explicitly with a reason — do not leave this blank.

## Forward plan

1. The concrete steps, in order.

## Reverse plan (matched to change type)

- **Pure code** → revert the squash-merge commit.
- **Schema** → tested down-migration (or expand/contract); name the migration + its reverse.
- **Risky feature** → behind a flag; reversal = flip the flag off.
- **Config** → revert config; note whether a restart/reload is required.
- Write the *actual* commands/steps, not just the checkbox.

## Verification

- **Worked:** how we confirm the change did what it should (test / metric / manual check).
- **Failed (detect):** which alert/metric/log/test would catch it failing.

## Blast radius

- What breaks if this goes wrong, who/what is affected, and whether the reversal is cheap or
  expensive (expensive reversals deserve extra review up front).

## Definition of Done

Meets the canonical [Definition of Done](../../CONTRIBUTING.md#definition-of-done) — including the
state-based **docs-freshness** item: docs reflect the current state of the system (CLAUDE.md
status/commands, README stage, `docs/data-model.md`, relevant ADRs), not only when a decision was made.

## Notes for the agent

Follow `CLAUDE.md`, the relevant ADRs, and `docs/data-model.md`. Do not push to `main`; stop after
pushing the branch for PR review.
