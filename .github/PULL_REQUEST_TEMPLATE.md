<!--
This template auto-loads into every PR. It is the filled-in change & reversal template
(docs/templates/change-and-reversal.md) — so PRs are no longer hand-copied. Fill every section;
delete nothing. Reversal is planned per change, matched to the change type — git revert only
undoes code.
-->

## Ticket: CON-<ID> — <title>

<!-- Link the ticket: docs/tickets/CON-<ID>-<slug>.md -->

### Forward plan

What are we changing, and how? The concrete steps.

### Reverse plan — tick the lever(s) that match the change type

- [ ] **Pure code change** → `git revert <sha>` (revert the squash-merge) + redeploy previous image.
- [ ] **Schema change** → tested **down-migration** (or **expand/contract**). Name the migration + its reverse.
- [ ] **Risky feature** → behind a **feature flag**; reversal = flip the flag off (no redeploy).
- [ ] **Bad deploy** → roll back to the previous image/release.
- [ ] **Data corruption** → restore/repair runbook (link the specific runbook).
- [ ] **Config change** → revert config; note whether a restart/reload is required.

Write the *actual commands or steps*, not just the checkbox.

### Verification

- How do we confirm the change worked? (test / metric / manual check — paste the `Tests run:` line)
- How would we **detect** failure in production? (which alert/metric/log)

### Blast radius

- What breaks if this goes wrong? Who/what is affected? Is the reversal cheap or expensive?

### Security criteria

- [ ] Security criteria on the ticket are met — or N/A with reason.

### Definition of Done

See [CONTRIBUTING.md → Definition of Done](../CONTRIBUTING.md#definition-of-done).

- [ ] Acceptance criteria met (incl. any **security** criteria on the ticket).
- [ ] Tests written and passing.
- [ ] Reverse plan filled in and *credible*, matched to the change type.
- [ ] Observability added (logs/metrics for the new behaviour).
- [ ] **Docs reflect the current state of the code** — CLAUDE.md status/commands, README stage,
      `docs/data-model.md` markers, relevant ADR — or **N/A with reason**.
- [ ] CI green, including security scans (Trivy/CodeQL).
- [ ] You can explain every line that merges.
