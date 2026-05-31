# CON-3 — Walking skeleton: Spring Boot app + Postgres + /health, containerized, with CI

**Type:** chore (foundation) · **Branch:** `chore/CON-3-walking-skeleton`

## Goal (user value)

Prove the entire plumbing path end-to-end before any feature exists: an HTTP request reaches a
running Spring Boot app, the app connects to a Postgres database, and CI builds/tests it through
the PR pipeline. This de-risks integration while it is cheap, and stands up the agent-safety
harness (CI verification loop) that every later feature relies on.

Not useful functionality yet — a `/health` endpoint is the whole feature. That is intentional:
**make it walk before we make it run.**

## Acceptance criteria

- [ ] A Spring Boot 3.x project on Java 21 (Maven or Gradle wrapper committed), virtual threads
      enabled (`spring.threads.virtual.enabled=true`).
- [ ] `GET /health` returns 200 with a small JSON body (e.g. `{"status":"UP"}`). Prefer Spring
      Boot Actuator's health endpoint over a hand-rolled one.
- [ ] The app connects to **Postgres running as a Docker container** (via `docker-compose.yml`),
      and the health check reflects DB connectivity (Actuator does this automatically).
- [ ] `docker-compose.yml` defines Postgres (and a Redis service stub for later) with config via
      environment variables, not hardcoded — and `.env.example` documents the variables.
- [ ] A `Dockerfile` for the app following the container baseline: multi-stage build, layered
      Spring Boot jar, non-root user, pinned base image by digest, `.dockerignore` present.
- [ ] A GitHub Actions CI workflow that, on PR: builds the app, runs tests, (placeholder for
      security scans to be added later). This becomes a required status check on `protect-main`.
- [ ] At least one trivial test that passes (proves the test harness runs in CI).
- [ ] README/docs note how to run locally (`docker compose up`, then hit `/health`).

## Security criteria (shifted left, per baseline)

- [ ] No secrets committed; DB password via env var; `.env` git-ignored, `.env.example` committed.
- [ ] Container runs as non-root with the baseline securityContext-friendly settings.
- [ ] Dependencies are official/pinned (verify any AI-suggested dependency against the registry).

## Forward plan

1. Scaffold Spring Boot project (web + actuator + JDBC/JPA + postgres driver + flyway).
2. Add `docker-compose.yml` (Postgres + Redis stub) and `.env.example`.
3. Wire datasource config from env; enable virtual threads; enable Actuator health.
4. Add multi-stage `Dockerfile` per container baseline.
5. Add GitHub Actions CI (build + test).
6. Add one trivial passing test.
7. Verify locally: `docker compose up`, app starts, `GET /health` → 200, DB shows healthy.

## Reverse plan (matched to change type)

- This is **new, additive code** on a branch — primary reversal is **not merging**, or
  `git revert` the squash-merge commit. Cheap and clean (nothing depended on yet).
- No DB schema/migrations beyond Flyway baseline yet; if a baseline migration is added, it ships
  with a tested down-migration (none expected for the skeleton).
- `docker compose down -v` tears down local services completely (the local-infra reversal).
- CI workflow reversal: remove the workflow file; status-check requirement is toggled off in the
  ruleset.

## Verification

- **Worked:** app boots, `GET /health` → 200 with DB component UP; CI is green on the PR;
  `docker compose up` reproducible from clean.
- **Failed (how we'd detect):** app fails to start (logs), health shows DB DOWN (Actuator),
  or CI red on the PR.

## Blast radius

Minimal — no production, no users, additive only. Worst case: the branch is wrong and we don't
merge it. This is exactly why the skeleton goes first: integration risk is contained here.

## Notes for the agent

Follow `CLAUDE.md` and `docs/security/container-baseline.md`. Keep the diff reviewable. Explain
non-obvious choices (project structure, why Actuator, how the layered jar works) so the builder
can understand every part. Do NOT push to main; stop after pushing the branch for PR review.