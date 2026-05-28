# ADR-0005: Develop inside WSL (Linux), not on the Windows host

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Builder + mentor

## Context

The builder is on Windows but has WSL2 (Ubuntu) installed, plus Docker Desktop. The entire
project is Docker + Linux containers (Postgres, Redis, Kubernetes via kind, LocalStack), and the
production target is Linux. Working from the Windows side while driving Linux containers creates
well-known friction: CRLF vs LF line-ending breakage in shell scripts, slow file I/O when Docker
bind-mounts Windows folders into Linux containers, and path mismatches.

It was also discovered that the builder's `git` and `node` were Windows binaries leaking through
the WSL PATH (`/c/Program Files/...`, `/mingw64/bin/...`) — exactly the cross-OS hazard to avoid.

## Decision

Do all development **inside WSL (Ubuntu)**, with the project living in the **Linux home
filesystem** (`~/projects/conduit`), never under `/mnt/c`. Use VS Code's **WSL remote** so the
editor UI runs on Windows while files, terminal, and runtime are all Linux. Install native Linux
`git`; install the JDK via SDKMAN! (see ADR-0006).

## Options considered

1. **Code on Windows, run tools from PowerShell** — familiar, but guarantees recurring CRLF,
   slow-bind-mount, and PATH papercuts. Rejected.
2. **Develop inside WSL (CHOSEN)** — Linux end-to-end, fast I/O, matches production, and the
   builder had already set WSL up.

## Consequences

- **Positive:** environment matches the Linux production target; no cross-OS papercuts; fast
  container file I/O.
- **Negative / trade-offs:** must keep work on the Linux side and avoid `/mnt/c` for the project;
  must ensure Linux tool versions take PATH precedence over Windows ones.
- **Follow-ups:** verify `which git` resolves to `/usr/bin/git` (Linux), not the Windows path.

## Reversal

Cheap and reversible — it is a workflow choice. The repo is portable; it can be cloned and built
on any Linux environment (including CI), which is itself a benefit of this decision.
