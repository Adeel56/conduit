# CON-14 — Docs reflect reality (kill the inception-era drift)

**Type:** docs · **Branch:** `docs/CON-14-docs-reflect-reality` · **Migration: none**

## Goal (value)

The top-level docs still describe an **inception, docs-only** repo — but `main` is an active Spring
Boot 3.5.14 / Java 21 app with CON-3 → CON-12 merged. That drift is dangerous: `CLAUDE.md` is the
operating guide every AI agent (and new contributor) reads first, and it currently asserts there is
"no application code, build file, or infrastructure yet" and "no build/test/lint commands yet,"
claims an **Argon2/RBAC** auth model that **does not exist**, and **duplicates** the Definition of
Done from `CONTRIBUTING.md` (a built-in "type a fact twice" drift source). `README.md`'s "we are
here" marker is a full stage behind, and `docs/data-model.md` describes a `Source` shape
(`slug`/`ingest_path` + `signing_secret`) that the code never built. This ticket makes the docs
**match the code on `main`**, so the guidance can be trusted.

## Key design decisions (the "why")

- **Document reality, not the plan.** Every claim was verified against the actual files on `main`
  before writing (`CLAUDE.md`'s own rule: *you must be able to explain every line that merges*, and
  *verify before you assert*). Where the original task snapshot disagreed with the repo, the repo won
  — see Notes for the one material discrepancy (CON-10/CON-11 are merged, not pending).
- **Built vs. planned is marked, not erased.** The design-intent sections (tech stack, security
  model, data model) stay — they record where Conduit is heading — but each unbuilt thing is tagged
  `planned`. The data model gets an explicit ✅/⬜ legend so a reader can't mistake design for code.
- **Single source of truth for the DoD.** The inline DoD checklist in `CLAUDE.md` is replaced by a
  one-line pointer to the canonical list in `CONTRIBUTING.md`. Removing the copy removes the drift.
  (The DoD *wording* in `CONTRIBUTING.md` is owned by a sibling ticket; this ticket only de-dupes.)
- **Auth is described exactly as built.** Stateless API-key filter; `prefix.secret`; salted SHA-256
  stored `salt:hash`; constant-time compare; scoped, hashed-at-rest, revocable; identical-401 on
  every failure. Argon2 password hashing, a `User` entity, and RBAC are marked **planned**.

## Acceptance criteria

- [ ] `CLAUDE.md` "Repository status" no longer claims inception / docs-only / no-build; it states
      the active app, CON-3 → CON-12 merged, Flyway V1–V5 (+ tested undos U2–U5), API-key auth,
      Testcontainers tests, distroless Dockerfile, CI with Trivy + CodeQL.
- [ ] `CLAUDE.md` "Commands" lists only commands that actually work: `./mvnw verify`, single-test
      by class and by `Class#method`, `docker compose up --build` (with the real services), and
      running the app from source (`./mvnw spring-boot:run` against a containerized Postgres).
- [ ] `CLAUDE.md` security section describes the real API-key auth and marks Argon2 password hashing
      + RBAC + the `User` entity as **planned / not built**.
- [ ] `CLAUDE.md` Definition of Done is a one-line pointer to `CONTRIBUTING.md` (no inline copy).
- [ ] `README.md` "we are here" marker is on **Build sprints**, and the "no product features yet"
      claim is gone / corrected.
- [ ] `docs/data-model.md` carries a build-status legend and per-entity ✅/⬜ markers; the `Source`
      section describes `ingest_key` (not `slug`/`signing_secret`) and marks `signing_secret`
      planned; the tenant-isolation section references **ADR-0010** instead of "a follow-up ADR".
- [ ] `./mvnw -B -ntp verify` is green (confirmatory — this change is docs-only).

## Forward plan

1. `CLAUDE.md`: rewrite the status section; replace "Commands"; fix the auth/security claim;
   replace the duplicated DoD with a pointer.
2. `README.md`: advance the lifecycle marker to Build sprints; correct the walking-skeleton intro.
3. `docs/data-model.md`: add legend + ✅/⬜ markers; correct the `Source` section; point isolation
   enforcement at ADR-0010 (Proposed, authored by the sibling ticket — referenced, not created here).
4. `./mvnw -B -ntp verify` as a confirmatory green gate; open the PR.

## Reverse plan (matched to change type)

- **Pure docs change** → `git revert <squash-sha>` (or revert the PR). No schema, no config, no
  runtime behaviour touched, so a code revert fully undoes it; no migration or flag to manage.
- Nothing to roll back at the infra/DB layer — `git` history *is* the lever here.

## Verification

- **Worked:** the three docs match `main` (status, commands, auth, data-model markers all check out
  against the actual files); `./mvnw -B -ntp verify` stays green (proves the docs edit didn't touch
  the build); a reader following the "Commands" block can build, test, and run the app.
- **Failed (detect):** a claim is contradicted by the repo (e.g. a documented command errors, or an
  entity marked ✅ has no code), or `verify` goes red (would mean a non-docs file was touched).

## Blast radius

**Low — docs only.** No source, no migration, no CI, no config. Worst case is a stale or imprecise
sentence, fixed by a follow-up edit; the reversal is a cheap `git revert`. The files touched are
`CLAUDE.md`, `README.md`, `docs/data-model.md`, and this ticket — no overlap with the sibling docs
tickets' lanes (`CONTRIBUTING.md`, the ADRs).

## Notes for the agent

Owns **only** `CLAUDE.md`, `README.md`, `docs/data-model.md`, and this ticket file. Does **not**
touch `CONTRIBUTING.md` (DoD wording — sibling ticket), `docs/adr/**` (ADR-0010 is authored by the
isolation sibling ticket; referenced here by name only), `docs/templates/**`, `.github/**`, or any
`src/`. **Discrepancy flagged:** the task snapshot expected CON-10 (destinations/routes, V5) and
CON-11 (source CRUD) to be *unmerged*, but on `origin/main` both are merged (commits for CON-10 and
CON-11 sit above CON-12, with V5, the `Destination`/`Route`/`Source`-CRUD entities, and their tests
all present). Per the verify-before-you-write rule, the docs were written to the repo's reality:
`Destination` and `Route` are marked **built ✅**, not 🚧. `Delivery`, `Attempt`, and `User` remain
**planned ⬜** (confirmed: no entities, no migrations).
