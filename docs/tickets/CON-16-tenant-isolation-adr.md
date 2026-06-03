# CON-16 — Write the deferred tenant-isolation enforcement ADR (ADR-0010)

**Type:** docs · **Branch:** `docs/CON-16-tenant-isolation-adr`

## Goal (user value)

`docs/data-model.md` promised "a follow-up ADR" on *how* tenant isolation is enforced, then the
mechanism was decided **implicitly** in CON-8/CON-9 code and never written up. That is exactly the
kind of load-bearing, security-critical decision the project requires an ADR for (a cross-org leak
is the worst-case bug). This ticket pays down that documentation debt: it writes **ADR-0010 —
Tenant isolation enforcement**, describing how isolation *actually* works today, the options
rejected, and the reversal path. To exercise the real ADR lifecycle, it opens as
**Status: Proposed** and is ratified to **Accepted** by PR review/merge — the author does not
self-ratify.

## Key design decisions (the "why")

- **Document reality, don't invent.** The ADR describes the mechanism already in the codebase
  (CON-8/CON-9), citing the concrete classes/methods, not an aspirational design. The drift between
  "data-model.md promised an ADR" and "code decided it silently" is the thing being fixed.
- **Proposed, not Accepted.** Per the ADR template, status is `Proposed` on open. Merging the PR is
  the ratification act (reviewer flips it to `Accepted`). This deliberately practices the
  inception-stage lifecycle rather than rubber-stamping.
- **Honest rejected options.** RLS is recorded as a *credible future defense-in-depth backstop*,
  not pretended away; schema/DB-per-tenant and client-supplied-org-id are rejected with their real
  trade-offs (the last being the literal leak vector).

## Acceptance criteria

- [ ] New `docs/adr/0010-tenant-isolation-enforcement.md` exists, in the `0000-template.md` shape
      (Status / Date / Deciders, Context, Decision, Options considered, Consequences, Reversal).
- [ ] **Status: Proposed** (not Accepted — ratification happens on merge).
- [ ] **Decision** documents the two load-bearing rules as implemented: `org_id` resolved **only**
      from `ApiKeyPrincipal` (never client input — no `orgId` param/body/path), and **every**
      tenant-scoped query filters by `org_id`; not-found and not-yours return an **identical 404**.
      Cites concrete code (`ApiKeyAuthenticationFilter`, `ApiKeyPrincipal`, `EventController`,
      `EventRepository.findByIdAndOrgId`, the `…ByOrgId` convention across Source/Destination/Route),
      and notes the cross-tenant tests (`EventInspectorIntegrationTest`) that pin it.
- [ ] **Options considered & rejected**: (a) Postgres RLS — noted as a credible *future* backstop,
      with why it isn't the sole mechanism now; (b) schema/DB-per-tenant — rejected (operational
      cost, cross-tenant queries, scale); (c) trusting a client-supplied org id — rejected (the leak
      vector).
- [ ] **Consequences**: positive (simple, testable, one pattern) and negative (relies on every
      query remembering the filter — mitigated by the repository convention + required tests; RLS
      would harden it).
- [ ] **Reversal**: ADRs are immutable; superseded by a future ADR (e.g. if RLS is adopted) via the
      `Supersedes` / `Superseded by` convention.
- [ ] `0010` confirmed as the next free ADR number (0001–0009 exist).
- [ ] `./mvnw verify` green (docs-only; build unaffected — confirmatory gate).

## Security criteria (shifted left)

- [ ] The ADR correctly states the contract that prevents the worst-case bug: tenant derived solely
      from the authenticated principal; identical-404 (no existence oracle); cross-tenant tests are
      the proof. No security claim in the ADR contradicts the code it cites.

## Forward plan

1. Branch `docs/CON-16-tenant-isolation-adr` from `origin/main`.
2. Read `0000-template.md` (shape), `0008`/`0009` (house style + Reversal), `data-model.md` (the
   deferred-ADR section), and the enforcement code (`SecurityConfig`, `ApiKeyPrincipal`,
   `ApiKeyAuthenticationFilter`, `EventController`, `EventRepository`) + the cross-tenant tests.
3. Write `docs/adr/0010-tenant-isolation-enforcement.md`, Status **Proposed**, citing the concrete
   classes/methods read.
4. `./mvnw -B -ntp verify`; capture the `Tests run:` line.
5. Conventional `docs:` commit (author `linux <adeeljahangir56@gmail.com>`, no AI trailer); push;
   open PR with the change-and-reversal body, noting the intentional **Proposed** status. Do not
   merge.

## Reverse plan (matched to change type)

- **Pure docs change** → `git revert <sha>` (or close the PR unmerged). Adds two Markdown files
  (the ADR + this ticket); touches no code, schema, config, or runtime behaviour.
- No migration, no config, no feature flag, no deploy — nothing to roll back beyond the files
  themselves.

## Verification

- **Worked:** ADR-0010 follows the template, is `Proposed`, accurately matches the cited code, and
  records the three rejected options with honest trade-offs; `./mvnw verify` is green (docs change
  can't break the build); the cross-reference from `data-model.md` to ADR-0010 (done by sibling
  CON-14, out of this lane) closes the "follow-up ADR" loop.
- **Failed (detect):** the ADR claims something the code doesn't do (e.g. RLS active today, or a
  `403`), is marked `Accepted` prematurely, or the build goes red — all caught in PR review / CI.

## Blast radius

Minimal — documentation only, additive, no runtime impact. The only failure mode is an *inaccurate*
ADR, caught by review against the cited code. Reversal is a trivial `git revert`.

## Notes for the agent

Stay strictly in lane: own **only** `docs/adr/0010-tenant-isolation-enforcement.md` and this ticket
file. Do **not** edit `docs/data-model.md` (sibling CON-14 updates its cross-reference), `CLAUDE.md`,
`CONTRIBUTING.md`, `.github/**`, or any `src/`. Only add an ADR index entry if `docs/adr/README.md`
already exists (it does not — so don't create one). Open the ADR as **Proposed**; the reviewer
ratifies to Accepted on merge. Do not push to main; stop after opening the PR.
