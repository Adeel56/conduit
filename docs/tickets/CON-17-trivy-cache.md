# CON-17 — Cache the Trivy gate (DB + Docker layers) to speed up CI

**Type:** chore (CI) · **Branch:** `chore/CON-17-trivy-cache` · **Owns:** `.github/workflows/ci.yml`

## Goal (value)

Make the `trivy-image-scan` job faster on warm runs **without weakening the security gate**. Two
costs dominate the job today and both repeat every run:

1. **Image build** (~66s) — a plain `docker build` with no layer cache, so every run re-resolves
   Maven dependencies and rebuilds the layered jar from scratch.
2. **Trivy DB download** — the vulnerability DB is fetched on (re)download as part of the scan.

This ticket adds standard, additive caching to both — keyed so the cache can never serve a stale
DB — so PR feedback stays fast as the codebase grows. It changes **nothing** about what the gate
flags or how it passes/fails.

## Key design decisions (the "why")

- **Trivy DB cache is date-keyed (`trivy-db-<YYYY-MM-DD>`), not content-keyed.** A vulnerability DB
  has no natural content key on our side — it changes upstream daily. Keying by UTC date means the
  DB is downloaded ~once per day; every new day produces a **new key → guaranteed cold cache →
  fresh DB**. `restore-keys: trivy-db-` lets a brand-new day warm-start from the previous day's dir,
  after which Trivy refreshes only what its own DB metadata says is out of date. **This is the
  control that prevents a stale-DB false-negative** — the cache cannot outlive the day it was
  fetched.
- **We own the DB cache explicitly instead of relying on the action's built-in cache.** The
  trivy-action has a built-in date-keyed cache, but making it an explicit `actions/cache` step (a)
  documents the key and the staleness reasoning in our own file, and (b) lets us point Trivy at one
  stable `TRIVY_CACHE_DIR`. We set `cache: false` on both trivy-action invocations so the action
  does not also save the same dir (no two cache entries fighting over one path).
- **Docker layer cache via buildx + `type=gha`.** `cache-to: type=gha,mode=max` caches intermediate
  build stages too, so warm runs reuse the heavy `dependency:go-offline` and `package` layers. We
  switch the build step from raw `docker build` to `docker/build-push-action` with `load: true` so
  the built image is loaded into the local daemon and Trivy can scan it by ref exactly as before.
- **Gate semantics are byte-for-byte unchanged.** `severity: HIGH,CRITICAL`, `ignore-unfixed: true`,
  `trivyignores: .trivyignore`, the SARIF step (`exit-code: 0`, `limit-severities-for-sarif`), and
  the gate step (`format: table`, `exit-code: 1`) are all untouched. Caching is purely about
  *when bytes are downloaded/built*, never about *what is flagged*.
- **Pinning preserved.** Every new action is pinned by full commit SHA with a trailing `# vX.Y.Z`
  comment, matching the repo convention and the security baseline (tags are mutable). `actions/cache`
  uses the same SHA the trivy-action itself vendors (v5.0.5). No existing action is un-pinned or
  changed.
- **Least privilege preserved.** Job `permissions` are unchanged (`contents: read`,
  `security-events: write` for the SARIF upload). Caching needs no extra scopes.

## Acceptance criteria

- [ ] Trivy DB cached across runs, keyed by UTC date (`trivy-db-<date>`) with `restore-keys:
      trivy-db-`, on a stable `TRIVY_CACHE_DIR`; a new day forces a fresh DB (no stale-DB miss).
- [ ] Docker build layers cached via buildx `cache-from`/`cache-to: type=gha`; the image is still
      built locally and scanned by Trivy (image loaded into the daemon, `load: true`).
- [ ] Gate semantics identical: still fails on **HIGH,CRITICAL**, still `ignore-unfixed: true`,
      still honors `.trivyignore`, same SARIF upload + category. No severity/exit-code change.
- [ ] All new actions SHA-pinned with `# vX.Y.Z` comments; no existing action un-pinned.
- [ ] Job `permissions` and overall file structure preserved; cache keys explained in comments.
- [ ] Cold-cache run still downloads the DB and builds the image (scan never stale); warm-cache run
      is faster.
- [ ] `./mvnw -B -ntp verify` green (Maven build is unaffected by CI YAML); Trivy/CodeQL green.

## Security criteria

- [ ] **No stale-DB false-negative possible.** The date in the cache key guarantees a fresh DB at
      least daily; `restore-keys` only warm-starts a new day's dir, it does not pin an old DB.
- [ ] **Gate not weakened.** Severity set, `ignore-unfixed`, `.trivyignore`, and exit codes are
      unchanged — the set of CVEs that turn the build red is identical.
- [ ] **Supply chain.** All actions remain SHA-pinned (no mutable tags); `actions/cache` matches the
      version the trivy-action already trusts. The `type=gha` layer cache stores build layers, never
      secrets (none are present in the build).
- [ ] **Least privilege.** No new permissions/tokens; cache uses the default GITHUB_TOKEN scopes.

## Forward plan

1. Add `TRIVY_CACHE_DIR` (stable workspace path) to the job `env`.
2. Add a `Compute DB cache date` step (`date -u +%F` → `$GITHUB_OUTPUT`).
3. Add an `actions/cache` step on `TRIVY_CACHE_DIR`, key `trivy-db-<date>`, restore-key `trivy-db-`.
4. Add `docker/setup-buildx-action`; replace `docker build` with `docker/build-push-action`
   (`load: true`, `cache-from`/`cache-to: type=gha,mode=max`).
5. Set `cache: false` on both trivy-action steps (DB caching is now ours; the action would
   otherwise double-cache the same dir).
6. `./mvnw -B -ntp verify`; push; confirm cold run populates caches and a warm re-run is faster.

## Reverse plan (matched to change type)

- **Config change (CI YAML only)** → `git revert <squash-sha>` of this PR restores the previous
  `docker build` + the trivy-action's built-in cache. No redeploy, no migration, no flag — the file
  is the only artifact. The change is **purely additive**: deleting the cache steps and reverting the
  build step leaves the gate exactly as it is today.
- **If a cache ever misbehaves** (e.g. a corrupt entry) → bump the cache key prefix (or delete the
  entry under Actions → Caches); this neither weakens nor bypasses the gate, it just forces a fresh
  download/build. No restart required.

## Verification

- **Worked:** CI green on this PR; the gate still fails on a fixable HIGH/CRITICAL (semantics
  unchanged — verified by diff: severity/exit-code/`ignore-unfixed`/`trivyignores` lines untouched);
  on the **second** (warm) run the cache steps report a hit and the `Build image` + Trivy scan steps
  are faster than the cold first run.
- **Detect failure:** Trivy step errors / DB download failures show in the job log; a stale DB would
  surface as a DB-age warning from Trivy (and the date key bounds staleness to <1 day regardless).
  A bad layer cache would surface as a build error in `Build image`.

## Blast radius

Very small. Only `.github/workflows/ci.yml` changes; no app code, no Dockerfile, no migration. Worst
case a cache is cold/corrupt → the job is merely as slow as today and still correct (it redownloads /
rebuilds). The gate cannot be loosened by caching. Reversal is a one-line-per-hunk `git revert` of a
single YAML file — cheap.

## Notes for the agent

Owns **only** `.github/workflows/ci.yml` and this ticket. Do **not** touch `codeql.yml`, the
`Dockerfile`, `.trivyignore`, `CLAUDE.md`, other docs, or any `src/`. Keep every action SHA-pinned.
Do not change the gate's severity, exit codes, `ignore-unfixed`, or `.trivyignore` usage. Do not push
to main; stop after opening the PR.
