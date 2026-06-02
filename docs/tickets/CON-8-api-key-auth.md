# CON-8 — API-key authentication & tenant resolution (the auth foundation)

**Type:** feat · **Branch:** `feat/CON-8-api-key-auth`

## Goal (user value)

The security foundation every dashboard/API endpoint stands on. A caller presents an API key;
Conduit verifies it (against a salted hash), resolves the owning **organization**, and makes that
org available to downstream handlers so they can enforce tenant isolation. After this, protected
endpoints are secured-by-default and the Inspector (next ticket) can filter events to the caller's
org. This is "who is the caller?" — the half that tenant isolation ("what may they see?") builds on.

## Key design decisions (the "why")

- **Keys stored as salted hashes, never plaintext** (data-model / ADR-0008): we only ever *verify*
  a presented key, never need to read it back, so a one-way hash is sufficient *and* safer. The
  raw key is shown to the user **once** at creation and never recoverable.
- **Short non-secret prefix** stored alongside the hash so a user can identify which key is which
  in a list (e.g. `ck_live_AbCd…`), and so lookup can be narrowed before hashing.
- **Scoped + revocable:** keys carry scopes (least privilege) and a `revoked_at` (soft revoke —
  auditable, reversible-ish, vs hard delete).
- **The key resolves an org, not just "authenticated true/false":** the whole point is multi-tenant
  — a valid key answers *which tenant*. That org id is what every protected handler filters by.
- **Ingest stays untouched:** `POST /ingest/{ingestKey}` is public-by-secret-URL and must NOT
  require an API key. Auth applies to dashboard/API routes only.

## Acceptance criteria

- [ ] Flyway `V3__api_keys.sql` (+ tested `U3` reverse) creating `api_keys` (`org_id`, `name`,
      `key_prefix`, `key_hash`, `scopes`, `last_used_at` nullable, `revoked_at` nullable, timestamps),
      with the lookup index on `key_prefix`/`key_hash` per `docs/data-model.md`.
- [ ] (If not already present) an `organizations` table or a clear interim for `org_id` — keep
      minimal; full org management is its own ticket. Document the choice.
- [ ] JPA `ApiKey` entity + repository.
- [ ] Secure key generation (CSPRNG) producing `prefix` + secret; only the **hash** is stored; the
      full key is returned to the caller exactly once.
- [ ] Verification path: extract key from the request (`Authorization: Bearer <key>` or documented
      header), hash, look up by prefix+hash, reject revoked keys, update `last_used_at`.
- [ ] A request-scoped way to expose the resolved **org id / principal** to handlers (e.g. Spring
      Security filter + an authentication principal, or a documented filter/interceptor approach).
- [ ] Protected routes return **401** when the key is missing/invalid/revoked; ingest remains public.
- [ ] A minimal seed path to create an API key for tests (service method, like SourceService — no
      unauthenticated key-minting endpoint).
- [ ] Integration tests (Testcontainers): valid key → authenticated + correct org resolved; missing
      key → 401; invalid key → 401; revoked key → 401; **ingest still works with no key**; and a
      **cross-tenant test** scaffold (org A's key never resolves org B) ready for the Inspector.
- [ ] `./mvnw verify` green; Trivy/CodeQL gates green; no secrets logged (never log raw keys/hashes).

## Security criteria (shifted left)

- [ ] Keys: CSPRNG-generated, salted-hashed at rest, never logged, shown once.
- [ ] Constant-time comparison where applicable to avoid timing leaks on verification.
- [ ] `401` responses don't leak whether a key exists vs is revoked.
- [ ] Revocation is immediate (revoked keys fail verification).
- [ ] Least privilege: scopes modeled even if only a default scope is used now.

## Forward plan

1. `V3` migration (+ `U3` reverse): `api_keys` (+ organizations interim if needed).
2. `ApiKey` entity + repository; key generator (prefix + CSPRNG secret + hash).
3. Auth filter/mechanism that verifies the key and resolves the org into the request principal.
4. Apply auth to dashboard/API routes; keep `/ingest/**` public.
5. Test-seed service for keys.
6. Integration tests: 401 paths, valid-key org resolution, ingest-still-public, cross-tenant scaffold.
7. `./mvnw verify`, local curl smoke (401 vs authed), gates green.

## Reverse plan (matched to change type)

- **Code (filter/entity/service)** → revert the squash-merge commit.
- **Schema** → tested `U3` drop (+ documented prod reverse: run U3, delete `flyway_schema_history`
  row v3); `docker compose down -v` locally.
- **Config** → revert any added properties; safe defaults.
- **Behavioral note:** turning on auth changes existing protected routes' responses (→ 401 without a
  key). Since the only existing routes are `/health` (keep public) and `/ingest/**` (must stay
  public), confirm those are explicitly excluded so this doesn't break them — that exclusion is the
  key thing to get right.

## Verification

- **Worked:** protected route → 401 without a key, 200 with a valid key resolving the right org;
  revoked key → 401; `/ingest/{key}` and `/health` still work with no API key; tests green.
- **Failed (detect):** ingest or health accidentally requires auth (would break CON-7 / the skeleton),
  valid key resolves wrong/no org, or raw key/hash appears in logs.

## Blast radius

Medium — auth is cross-cutting and it's easy to accidentally lock down `/health` or `/ingest`. The
explicit public-route allowlist + the "ingest still works" test bound that risk. Otherwise additive
(new table, new filter). Revert is clean.

## Notes for the agent

Follow `CLAUDE.md`, `docs/data-model.md`, `docs/security/security-baseline.md`. Prefer a clear,
idiomatic Spring Security setup; explain the filter chain and how the principal/org is resolved and
exposed to handlers, since this is the foundation the Inspector and all future endpoints depend on.
CRITICAL: keep `/health` and `/ingest/**` public — add an explicit test that ingest still works with
no key. Reversible migration mandatory. No raw keys/hashes in logs. Do not push to main; stop after
pushing the branch for PR review.
