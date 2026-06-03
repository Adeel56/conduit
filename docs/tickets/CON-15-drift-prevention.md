# CON-15 — Drift prevention (make "code changed, doc didn't" mechanically catchable)

**Type:** docs/process · **Branch:** `docs/CON-15-drift-prevention` · **Status:** In review

## Goal (value)

A docs-vs-code audit (`docs/audit/2026-06-04-docs-vs-implementation-audit.md`) found the docs
froze at "inception" while nine tickets (CON-3..CON-12) merged a live Spring Boot app. The root
cause is not laziness — it is that **nothing in the process mechanically catches "the code changed
but the doc didn't."** The Definition of Done only required "Docs/ADR updated *if a decision was
made*", but doc rot is a **state** change, not a decision, so that conditional excludes exactly the
failure that happened: the system's state moved (inception → running app) without any single
"decision" event to trip the checkbox.

This ticket installs the **drift-prevention** mechanisms so freshness is a per-change gate, not a
hope. It is process/docs-only — no application behaviour changes.

## Key design decisions (the "why")

- **State-based, not decision-based.** Reword the DoD docs item from a conditional ("if a decision
  was made") to a standing requirement: docs must **reflect the current state** of the system every
  change. This closes the exact gap the audit found.
- **One canonical DoD.** CONTRIBUTING.md becomes the single source of truth for the Definition of
  Done; CLAUDE.md, the PR template, and the ticket template **point** at it instead of duplicating
  it. Duplicated checklists drift independently — that is itself a drift source. (CON-14 owns
  collapsing CLAUDE.md's duplicated DoD into a pointer here; this ticket makes CONTRIBUTING.md the
  authoritative copy and the PR/ticket templates link to it.)
- **Mechanical surface, not willpower.** A GitHub `PULL_REQUEST_TEMPLATE.md` auto-loads the
  change & reversal template into *every* PR with an explicit **docs-freshness checkbox**. PRs stop
  being hand-copied (a step easy to skip), and the freshness check is now in front of the author at
  the moment they open the PR — the moment they know what changed.
- **Trackable ticket state.** New tickets carry an explicit **Status** field (Proposed | In progress
  | In review | Merged | Superseded) so a ticket's state is *recorded*, not inferred from whether a
  branch or PR happens to exist. The audit had to *guess* which tickets were done; a Status line
  removes the guessing.
- **Prevention, not just detection.** This ticket deliberately installs the *preventive* controls
  (checklist wording + templates). A future automated detector (e.g. a CI doc-freshness linter) is a
  natural follow-up but is out of scope here — the cheapest, highest-leverage fix is to make the
  human gate state-based and unavoidable first.

## Acceptance criteria

- [ ] **`.github/PULL_REQUEST_TEMPLATE.md`** exists and GitHub auto-loads it into new PRs. It is
      based on `docs/templates/change-and-reversal.md` (forward plan, reverse plan by change type,
      verification, blast radius, security criteria) and adds an explicit **docs-freshness
      checkbox**: "Docs reflect the current state of the code (CLAUDE.md status/commands, README
      stage, data-model markers, relevant ADR) — or N/A with reason". It links to the DoD.
- [ ] **CONTRIBUTING.md Definition of Done** reworded: the docs item is now **state-based** ("docs
      reflect the current state of the system") rather than conditional ("if a decision was made").
      The other DoD items are unchanged. CONTRIBUTING.md is marked as the canonical DoD.
- [ ] **Ticket template carries a Status field.** `docs/templates/` had no ticket template, so one
      is created (`docs/templates/ticket-template.md`) matching the shape of existing tickets
      (e.g. `docs/tickets/CON-12-organizations.md`) and including a **Status** field
      (Proposed | In progress | In review | Merged | Superseded).
- [ ] This ticket file exists at `docs/tickets/CON-15-drift-prevention.md` in the existing structure.
- [ ] `./mvnw -B -ntp verify` green (confirmatory — this is a docs/process change; the build is
      unaffected). Trivy/CodeQL gates green.

## Security criteria (shifted left)

- [ ] **No security-relevant behaviour changes.** This is documentation/process only — no code,
      schema, config, dependency, or manifest changes. Blast radius for the security posture is nil.
- [ ] Indirectly *strengthens* the security process: the DoD already requires "security criteria on
      the ticket"; making docs-freshness a standing gate keeps the security docs (threat model,
      baselines) honest against the code as it evolves, instead of letting them rot silently.

## Forward plan

1. Create `.github/PULL_REQUEST_TEMPLATE.md` from the change & reversal template + docs-freshness
   checkbox; keep it short and checkbox-driven; link to the canonical DoD.
2. Reword the CONTRIBUTING.md DoD docs item to be state-based; mark CONTRIBUTING.md as canonical.
3. Create `docs/templates/ticket-template.md` (none existed) with a Status field, matching the
   existing ticket shape.
4. Write this ticket (`docs/tickets/CON-15-drift-prevention.md`).
5. `./mvnw -B -ntp verify` (confirmatory green gate); commit; open PR with the change & reversal body.

## Reverse plan (matched to change type)

- **Pure docs/process change (Markdown + a GitHub template file)** → `git revert <squash-merge sha>`.
  That fully restores the previous DoD wording and removes the new template files; nothing depends on
  them at runtime. No schema, no config, no deploy, no feature flag involved.
- The PR description carries this same reverse plan (revert the squash-merge).

## Verification

- **Worked:** opening a fresh PR pre-populates the change & reversal body including the docs-freshness
  checkbox; the CONTRIBUTING.md DoD reads as state-based; a new ticket created from the template
  carries a Status line; `mvnw verify` is green (capture the `Tests run:` line in the PR).
- **Failed (detect):** the PR template does not auto-load (wrong path/filename under `.github/`), the
  DoD still reads "if a decision was made", or the build unexpectedly goes red (would indicate this
  change touched more than docs — it must not).

## Blast radius

Minimal — process/docs only. No application code, schema, config, or dependency is touched, so there
is no runtime blast radius. Worst case is a wording or Markdown-link nit, fixable in a follow-up
docs commit; the reversal is a cheap single-commit revert. The *upside* is large: it closes the
process gap that let the docs rot across nine merged tickets.

## Definition of Done

Meets the canonical [Definition of Done](../../CONTRIBUTING.md#definition-of-done), including the new
state-based **docs-freshness** item. For this ticket specifically: the docs-freshness check is
**N/A for the data model / ADRs** (no behaviour changed), and the *process docs themselves* are what
this change updates — CONTRIBUTING.md (DoD), the new PR template, and the new ticket template are all
brought to the current state of how we work. CLAUDE.md's duplicated DoD is left to **CON-14** (which
owns CLAUDE.md and collapses it into a pointer to CONTRIBUTING.md) — out of this ticket's lane.

## Notes for the agent

Process/docs-only. Owned files: `.github/PULL_REQUEST_TEMPLATE.md`, `CONTRIBUTING.md` (DoD wording
only), `docs/templates/ticket-template.md`, and this ticket file. Do **not** touch CLAUDE.md
(CON-14), README.md, `docs/data-model.md`, `docs/adr/**`, `.github/workflows/**`, or any `src/`.
Conventional Commit `docs:`. Do not push to `main`; stop after opening the PR for review.
