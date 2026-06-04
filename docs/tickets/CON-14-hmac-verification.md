# CON-14 — HMAC signature verification (prove a webhook really came from the source)

**Type:** feat (security) · **Branch:** `feat/CON-14-hmac-verification` · **Migration: V7 if needed**

> Build AFTER CON-13 is merged. Independent of the delivery engine's runtime, but sequenced after so
> tickets describe the real merged codebase.

## Goal (value)

Right now anyone who learns a source's secret ingest URL can POST a forged webhook. Real providers
(Stripe, GitHub) sign each webhook with an HMAC over the body using a shared secret, and send the
signature in a header. This ticket lets a source carry a **signing secret** and verify the incoming
signature, rejecting forgeries — closing the authenticity gap the ingest design always anticipated.

## Key design decisions (the "why")

- **HMAC, not a second password:** the provider and Conduit share a secret; the provider sends
  `signature = HMAC(secret, raw_body)` in a header. Conduit recomputes it over the *raw* bytes and
  compares. A forger without the secret can't produce a valid signature.
- **Over the RAW body, before any parsing:** the signature covers exact bytes. CON-7 already stores
  the raw payload faithfully — verify against those same bytes (re-serializing would break the MAC).
- **Constant-time comparison:** compare signatures in constant time (timing-attack safe), same
  discipline as the API-key verify in CON-8.
- **Opt-in per source, with a grace path:** a source has a nullable `signing_secret`. If set,
  ingest **requires** a valid signature (reject `401`/`400` on missing/invalid). If null, behaviour
  is unchanged (backward-compatible — existing sources keep working). Document the rollout.
- **Secret handling:** the signing secret is generated server-side (CSPRNG) and shown once
  (CON-8/CON-11 show-once pattern). Stored so Conduit can recompute the MAC — note this means it's
  recoverable-for-compute (unlike a one-way-hashed API key); store it encrypted-at-rest if the
  baseline requires, else document the tradeoff and defer encryption to its own ticket.

## Acceptance criteria

- [ ] Schema: add `signing_secret` (nullable) to `sources` — if a column add is needed it's **V7**
      (+ tested `U7`); confirm CON-13 took V6 so you don't collide. JPA mapping updated.
- [ ] A source can be issued a signing secret (service method + endpoint extending CON-11's source
      management): returns the secret **once**; rotation supported (new secret invalidates old).
- [ ] Ingest verification: when a source has a `signing_secret`, `POST /ingest/{ingestKey}` must
      carry a valid HMAC signature header over the raw body, verified constant-time. Invalid/missing
      → reject (document `401` vs `400`); valid → store + 202 as today. Sources with no secret are
      unaffected.
- [ ] Configurable: the header name and HMAC algorithm (e.g. SHA-256) — sane defaults, overridable.
- [ ] Integration tests (Testcontainers): valid signature → 202; tampered body → reject; missing
      signature on a secret-bearing source → reject; a source with NO secret → still 202 (backward
      compat); rotation invalidates the old secret; constant-time compare in place.
- [ ] `./mvnw verify` green; round-trip if a migration was added; Trivy/CodeQL green.

## Security criteria

- [ ] Signature verified over the exact raw bytes, before parsing; constant-time comparison.
- [ ] Signing secret CSPRNG-generated, shown once, never logged.
- [ ] Reject is generic (no oracle about why it failed beyond what's necessary).
- [ ] Backward compatible: secret-less sources keep working (no surprise lockout).

## Forward plan

1. `V7` (+ `U7`) adding `sources.signing_secret` (nullable), or document why no migration.
2. Secret generation + show-once issue/rotate, extending source management.
3. HMAC verifier (raw-bytes, configurable algo/header, constant-time).
4. Wire into the ingest path: enforce only when the source has a secret.
5. Tests: valid / tampered / missing / no-secret / rotation.
6. `./mvnw verify`, round-trip, gates.

## Reverse plan (matched to change type)

- **Schema (nullable column)** → `U7` drops the column; nullable + backward-compatible, so existing
  rows are unaffected by the add or the drop.
- **Code** → revert; with verification reverted, ingest behaves exactly as CON-7 again.
- **Behavioral:** enabling a secret on a source is itself reversible (clear the secret → back to
  unsigned). The risky moment is locking out a live provider by enabling enforcement before the
  provider is configured — document a safe rollout (issue secret → configure provider → confirm
  signed deliveries arrive → then it's enforced).

## Verification

- **Worked:** a signed request with the right secret stores + 202; tampering the body fails; a
  secret-less source still works; rotating the secret invalidates the old one.
- **Failed (detect):** a forged/unsigned request to a secret-bearing source gets through, a
  secret-less source breaks, or the secret appears in logs.

## Blast radius

Medium — touches the public ingest path, but enforcement is opt-in per source, so default behaviour
is unchanged. The lockout risk is operational (enabling before the provider signs), mitigated by the
documented rollout. Schema change is a nullable column (cheap, reversible).

## Notes for the agent

Follow `CLAUDE.md`, `docs/security/security-baseline.md`, ADR-0008. Verify over the RAW stored bytes
(reuse CON-7's faithful payload), constant-time compare (mirror CON-8's key verify). Make enforcement
strictly opt-in per source for backward compatibility. **Confirm CON-13 owns V6; if you add a
migration it is V7.** Explain HMAC verification and why it's over raw bytes. Do not push to main;
stop after pushing the branch for PR review.
