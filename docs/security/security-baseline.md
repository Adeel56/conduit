# Conduit — Security Baseline

The principle behind all of it: **least privilege applies at every layer simultaneously** —
OS user, container, pod, network, cloud IAM, and application RBAC. Same idea, six layers.

## Identity & access

- Passwords hashed with **argon2** (never store plaintext or weak hashes).
- Session or JWT auth for the dashboard.
- API keys are **scoped** (least privilege), **hashed at rest**, and **rotatable**.
- Application **RBAC**: owner / admin / member / viewer.
- **Tenant isolation** enforced at the row level — org A can never read org B's data. This is
  tested explicitly, because a leak here is the worst-case bug for a multi-tenant product.

## Webhook-specific controls

- **Inbound signature verification (HMAC):** only accept genuine webhooks from configured sources.
- **Outbound SSRF protection:** destinations are user-supplied URLs, so block private,
  link-local, and cloud-metadata ranges; resolve-then-validate to defeat DNS rebinding.
- **Replay protection + payload caps:** reject stale signed requests; cap body size; guard
  against decompression bombs.

## Secrets

- `.gitignore` + `.dockerignore` discipline — secrets and `.git` never enter the repo or image.
- Encrypt signing secrets and PII at rest.
- Kubernetes Secrets in cluster; the "real story" for production is Sealed Secrets or an
  External Secrets operator (no plaintext secrets in git, ever).

## Supply chain

- Pinned lockfiles; reproducible builds.
- **Dependabot + CodeQL** (free via Student Pack) on the repo.
- **Trivy** image scanning in CI; fail the build on critical CVEs.
- **AI-suggested dependencies must be verified against the official registry before adding.**
  (Defends against hallucination → dependency-confusion attacks.)
- Least-privilege CI tokens; prefer OIDC over long-lived cloud keys.

## Perimeter

- TLS everywhere (cert-manager + Let's Encrypt in cloud).
- Kubernetes **NetworkPolicies, default-deny**: DB reachable only from the API pod; workers
  reach Redis but not the open internet beyond allowed egress; Postgres never publicly exposed.
- Ingress exposes only what is needed.
- Per-key / per-IP rate limiting for abuse; **delegate volumetric DDoS to the edge.**

## Data & audit

- Retention/deletion policy for stored payloads (they may contain PII).
- **Audit log** of sensitive actions (key rotation, event replay, role changes), tamper-evident.

## Enforcement

This baseline is not aspirational. Dockerfiles and Kubernetes manifests are **reviewed against
the container baseline checklist**, and security controls appear as **acceptance criteria on
the tickets that introduce them** — so "secure" is a definition-of-done, not a hope.
