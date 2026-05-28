# Conduit — Threat Model (Inception v0.2)

A threat model has a **boundary**. Defining that boundary is the senior skill: we defend the
threats that apply to *our* system and consciously delegate or exclude the rest. Trying to
defend everything boils the ocean and still misses the real attack surface.

## What Conduit is, in security terms

- A **public ingest endpoint** that accepts webhooks from third parties (server-to-server).
- A **dashboard API + UI** used by humans in a browser.
- A **delivery worker** that makes outbound HTTP calls to **user-supplied URLs**.
- A **multi-tenant** datastore (many orgs, strict isolation).

## Entry points and STRIDE pass

| Entry point | Primary threats | Control (and the stage it lands in) |
|---|---|---|
| Ingest endpoint | Spoofed/forged webhooks; flooding; oversized/decompression-bomb payloads; replay | HMAC signature verification; per-key rate limiting + load shedding; payload size caps; replay-window checks (Build) |
| Dashboard API | Broken auth; tenant data leakage; CSRF | Argon2 password hashing; session/JWT auth; **row-level tenant isolation**; strict CORS allowlist (Design + Build) |
| API keys | Leaked/over-scoped keys | Scoped + hashed-at-rest + rotatable keys; least-privilege scopes (Build) |
| **Outbound delivery** | **SSRF** — a tenant points a destination at `169.254.169.254` or internal IPs to pivot | **Block private/link-local/metadata ranges; egress allowlist; NetworkPolicy default-deny** (Build + Deploy) |
| Perimeter | Volumetric DDoS; TLS stripping | **Delegate** volumetric to edge (Cloudflare); TLS everywhere via cert-manager (Deploy) |
| Supply chain | Malicious/typo/hallucinated dependency | Pinned lockfiles; Dependabot + CodeQL; Trivy; **AI-suggested deps verified against official registry**; human review gate (Build/CI) |
| Container/runtime | Container escape; privilege escalation | Non-root; read-only rootfs; drop capabilities; no-new-privileges; resource limits (see container baseline) (Build + Deploy) |
| Data at rest | PII/secret exposure | Encrypt signing secrets + PII; retention/deletion policy; tamper-evident audit log (Design + Manage) |

## Resilience / availability threats (these are ours to build)

- **Application-layer flooding** → per-key & per-IP rate limiting (Redis sliding window).
- **Overload** → **load shedding** (fast 429 instead of accepting everything and dying).
- **Failing dependency** → **circuit breaker** (stop hammering a down destination).
- **Resource contention** → **bulkheads** (isolated pools so one tenant can't sink the ship).
- **Volumetric DDoS** → **delegated** to the edge provider; we cannot and should not rebuild
  a global scrubbing network.

## Explicitly OUT of scope (and why) — the boundary

These were considered and consciously excluded because they belong to a different discipline
or layer. Listing them is the deliverable.

- **Active Directory exploitation, BloodHound** — corporate on-prem Windows/AD security. We
  run cloud-native Linux/Kubernetes with no AD. Different domain (internal pentesting/red team).
- **Fuzzing network switches, recursive sinkholing** — physical/ISP network-infrastructure
  defense. We don't own the hardware; the cloud provider / edge does. Delegated.
- **Cobalt Strike in-memory beacons, Initial Access Brokers** — post-exploitation and
  attacker-ecosystem topics for enterprise SOC/blue teams. Not an architectural concern for us.
- **DNS tunneling / exfiltration** — defended *indirectly* by our least-privilege egress posture
  (SSRF blocks + NetworkPolicy default-deny), not as a named monitoring system.
- **Honey tokens / deception tech** — clever, but an advanced detection layer for a mature
  program. Possible future stretch (a decoy API key that alerts if ever used); not v1.

## In scope and OURS

- **AI package hallucination → dependency confusion.** Directly relevant because our workflow
  uses AI assistance. Defended by lockfile pinning, registry verification of any AI-suggested
  dependency, Dependabot/CodeQL/Trivy, and the PR review gate.
