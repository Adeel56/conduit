# Change & Reversal Template

Every ticket carries this. The point: **reversal is planned per change, matched to the change
type** — because `git revert` only undoes *code*. A deploy may also involve a schema change, a
config change, and cache state. `git revert` does nothing about a column you already dropped or
a cache full of bad data. Knowing *which lever to pull* is the skill.

Copy this into each ticket / PR description.

---

## Ticket: <ID> — <title>

### Forward plan
What are we changing, and how? List the concrete steps.

### Reverse plan — pick the lever(s) that match the change type
- [ ] **Pure code change** → `git revert <sha>` + redeploy previous image.
- [ ] **Schema change** → tested **down-migration**. If not cleanly reversible, use the
      **expand/contract** pattern (add new → migrate → remove old later) so a safe rollback
      point always exists.
- [ ] **Risky feature** → ship behind a **feature flag**; reversal = flip the flag off (no
      redeploy needed — the fastest undo of all).
- [ ] **Bad deploy** → roll back to the previous image/release.
- [ ] **Data corruption** → restore/repair runbook (point to the specific runbook).
- [ ] **Config change** → revert config; note whether a restart/reload is required.

Write the *actual commands or steps*, not just the checkbox.

### Verification
- How do we confirm the change worked? (test, metric, manual check)
- How would we **detect** it had failed in production? (which alert/metric/log)

### Blast radius
- What breaks if this goes wrong? Who/what is affected?
- Is the reversal cheap or expensive? (Expensive reversals deserve extra review up front.)

---

## Why a doc, not just git history

When something breaks at 3am, you do not want to *reverse-engineer* how to undo a change under
pressure. The reverse plan was written calmly, in advance, by the person who understood the
change best — their past self. That is what turns a panicked incident into a procedure.
