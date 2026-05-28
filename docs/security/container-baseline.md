# Conduit — Container & Runtime Security Baseline

Every image we build and every pod we deploy is reviewed against this checklist. It is a
**review gate**, not a suggestion.

## Image build

- [ ] **Multi-stage build** — build tools/compilers stay in a throwaway stage; the final image
      ships only the runtime artifact.
- [ ] **Spring Boot layered jar** — dependency layers cached separately from app code for fast
      rebuilds.
- [ ] **Minimal / distroless base** — no shell, no package manager in the final image.
- [ ] **Base image pinned by digest** (`@sha256:...`), never a floating `:latest`.
- [ ] **`.dockerignore`** present — secrets, `.git`, local junk never baked in.
- [ ] **Dedicated non-root user** created and used (`USER appuser`).
- [ ] **Trivy scan** in CI passes (no unaddressed criticals).

## Runtime (Kubernetes securityContext) — defense in depth

Set these even though the image already runs as non-root, because a manifest could otherwise
override the image's user. Enforce at **both** layers.

- [ ] `runAsNonRoot: true` and an explicit non-zero `runAsUser`.
- [ ] `readOnlyRootFilesystem: true` (mount an `emptyDir` for any needed scratch space).
- [ ] `allowPrivilegeEscalation: false`.
- [ ] `capabilities: { drop: ["ALL"] }` — add back only what is genuinely required (usually none).
- [ ] `seccompProfile: { type: RuntimeDefault }`.

## Resources & health

- [ ] **Requests** set (minimum guaranteed; used for scheduling).
- [ ] **Limits** set (hard ceiling; over-memory → OOMKilled, over-CPU → throttled).
- [ ] **Liveness, readiness, and startup probes** defined.

## Why this matters (the one-line interview answer)

Least privilege at the container layer means: if an attacker gets code execution inside the
container, there is **no shell to use, no root to escalate to, no writable filesystem to
persist in, no extra capabilities to abuse, and a hard resource ceiling** that prevents one
compromised or runaway container from taking down the node.
