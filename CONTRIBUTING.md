# Contributing to Conduit (How We Work)

You are simulating a team. Even working solo, you follow the rituals a real team follows —
that discipline *is* the thing being learned.

## Branching model: trunk-based with short-lived branches

- `main` is **always releasable**. Never commit directly to `main`.
- Every change happens on a short-lived branch off `main`:
  - `feat/<ticket-id>-<slug>` — a feature
  - `fix/<ticket-id>-<slug>` — a bug fix
  - `chore/<ticket-id>-<slug>` — tooling/infra/docs
- Branches are **small and short-lived** (hours to a couple of days), so integration is
  frequent and merge pain stays low. Long-lived branches are how teams get into merge hell.

## Enforced protection on `main` (ruleset: `protect-main`)

The rules above are not just convention — they are **technically enforced** by a GitHub
ruleset, so "no dirty pushes to main" is impossible, not merely discouraged:

- **Require a pull request before merging** — no direct pushes to `main`; every change is a PR.
- **Required approvals: 0** — deliberate solo accommodation. The *PR pipeline* (diff, checks,
  conversation resolution) is mandatory; the one gate needing a *second human* is removed
  because a one-person team has none. The self-review ritual (read your own "Files changed"
  diff as if a stranger wrote it) substitutes for it, and CI checks act as the non-human gate.
- **Require status checks to pass** + **require branches up to date** — enabled now; the
  specific checks get attached once CI exists. Merge will be blocked on red CI.
- **Require conversation resolution** — review comments can't be silently ignored.
- **Block force pushes** + **restrict deletions** — `main`'s history can't be rewritten or deleted.
- **Bypass list: empty** — rules apply to the admin (me) too. Real orgs keep a narrow, *audited*
  break-glass path for true production emergencies; we defer that to a runbook until we have
  production, because right now the lesson is feeling the friction of our own rules.

Deferred (known, not needed yet): require signed commits, require linear history (optional —
aligns with our squash convention), require successful deployment.

## The lifecycle of one change

1. **Ticket first.** No code without a ticket. The ticket carries the
   [change & reversal template](docs/templates/change-and-reversal.md).
2. **Branch** off the latest `main`.
3. **Commit** using Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`,
   `test:`. These drive the changelog and make history readable.
4. **Keep up to date** with `git rebase main` on your branch (rebase your *own* unpushed work
   to keep history linear; never rebase shared/public history others have based work on).
5. **Open a PR.** The PR description = the filled-in change & reversal template.
6. **CI must pass** — build, tests, lint, security scans (Trivy/CodeQL).
7. **Review.** Even solo, review your own PR with fresh eyes against a checklist, or use an
   AI reviewer as a second pair of eyes — then make the human call. *You* are accountable for
   what merges, including any AI-suggested code or dependencies.
8. **Merge** (squash for a clean history) → branch auto-deleted.
9. **`main` deploys** to staging via CI/CD.

## Definition of Done

This is the **canonical** Definition of Done — the single source of truth. Other docs (CLAUDE.md,
the PR template, the ticket template) point here rather than duplicating it, so it stays in one place.

- [ ] Acceptance criteria met (incl. any **security** criteria on the ticket).
- [ ] Tests written and passing.
- [ ] Reverse plan filled in and *credible*.
- [ ] Observability added (logs/metrics for the new behaviour).
- [ ] **Docs reflect the current state of the system** — not only when a decision was made. Doc rot
      is a *state* change, not a decision, so "update docs if a decision was made" misses it. Check
      the drift surface every change touches: CLAUDE.md status/commands, README stage,
      `docs/data-model.md` markers, and any relevant ADR. If nothing drifted, say so (N/A) rather
      than skipping the check.
- [ ] CI green, including security scans.

## On using AI (Claude Code etc.) the right way

The goal is **10x speed without losing understanding**. Rules:
- You must be able to **explain every line** that merges under your name.
- **Verify AI-suggested dependencies** against the official registry before adding them
  (hallucination → dependency-confusion risk).
- AI accelerates the *typing*, not the *deciding*. Decisions get ADRs; you own them.

## Decisions get recorded

Any significant choice → an [ADR](docs/adr/). What we decided, why, what we rejected,
and how to reverse it.
