# CON-4 — Integration test with Testcontainers (boot app against real Postgres in CI)

**Type:** chore (hardening) · **Branch:** `chore/CON-4-testcontainers-integration`

## Goal (value)

Move the "full plumbing works" proof from local-only into CI. Today CI runs only a trivial unit
test that does not start the Spring context (CI has no database). This ticket adds an integration
test that boots the real application against an **ephemeral Postgres** spun up by Testcontainers,
and asserts `GET /health` returns 200 UP — so every future PR proves end-to-end wiring
automatically, not just locally by hand.

## Concept (why Testcontainers)

Testcontainers programmatically starts a real Postgres in a throwaway Docker container for the
duration of the test, then tears it down. The test runs against the *same database engine as
production*, with perfect isolation and no manual setup. This is the standard way to write
trustworthy integration tests for DB-backed services.

## Acceptance criteria

- [ ] Add Testcontainers (JUnit 5 + postgresql modules) as test-scope dependencies (verify
      versions against Maven Central; Spring Boot 3.x has Testcontainers support via its BOM).
- [ ] An integration test boots the full Spring context against a Testcontainers Postgres
      (use Spring Boot's `@ServiceConnection` so the datasource auto-wires to the container).
- [ ] The test calls `GET /health` (via `TestRestTemplate`/`MockMvc`) and asserts **200** with
      status UP, and that Flyway's baseline migration applied against the container.
- [ ] The test runs in CI (CI already has Docker available on `ubuntu-latest` runners).
- [ ] The existing trivial `SmokeTest` may stay or be folded in — keep at least one fast unit test.
- [ ] CI stays green; total CI time remains reasonable.

## Security criteria

- [ ] No secrets; Testcontainers manages throwaway credentials internally.
- [ ] Dependencies official and pinned (verified against the registry).

## Forward plan

1. Add Testcontainers BOM/deps (test scope).
2. Write the integration test (`@SpringBootTest` + Testcontainers Postgres + `@ServiceConnection`).
3. Assert `/health` UP and Flyway applied.
4. Confirm green locally (`./mvnw verify` — needs Docker running) and in CI.

## Reverse plan (matched to change type)

- **Pure additive test + test-scope deps** → revert the squash-merge commit; nothing in
  production code depends on it. Cheap.
- No schema, no config, no runtime change.

## Verification

- **Worked:** `./mvnw verify` runs the integration test green locally and in CI; the test
  genuinely boots the context against a container (not mocked).
- **Failed (detect):** CI red, or the test passes without actually starting a container (review
  the test to ensure it boots the real context).

## Blast radius

Minimal — test code only, additive. Worst case: flaky/slow test, which we'd see in CI and fix.

## Notes for the agent

Follow `CLAUDE.md`. Use Spring Boot's first-class Testcontainers integration
(`@ServiceConnection`) rather than manual datasource wiring. Keep the diff small and reviewable.
Explain the test's structure so the builder understands how the container lifecycle ties to the
test lifecycle. Do not push to main; stop after pushing the branch for PR review.
