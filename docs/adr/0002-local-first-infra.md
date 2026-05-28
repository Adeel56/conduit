# ADR-0002: Local-first infrastructure, with one real-cloud capstone

- **Status:** Accepted
- **Date:** 2026-05-25
- **Deciders:** Solo builder + mentor

## Context

We need the *full* real devops toolchain (Kubernetes, Terraform including teardown, object
storage, managed-service patterns) for the learning to be authentic. But running everything in
a paid cloud the whole time costs money and creates fear around destructive commands like
`terraform destroy`, which is exactly the command we most want to practice fearlessly.

The builder has a GitHub Student Pack (free credits on DigitalOcean, Azure, etc.).

## Decision

Build and self-host **everything locally** for the entire development phase using a real
toolchain — `kind` (real Kubernetes), LocalStack (AWS-compatible APIs), self-hosted
Postgres/Redis, Terraform against local providers. Then do **one real-cloud capstone deploy**
near the end using Student Pack credits, to produce a genuine production deployment.

## Options considered

1. **Cloud the whole way** — most "real," but costs money, slows iteration, and makes
   destructive practice scary. Rejected.
2. **Local only, never touch cloud** — free and safe, but misses the genuine-article
   experience and the resume value of a real deploy. Rejected.
3. **Local-first + one cloud capstone (CHOSEN)** — fearless free practice with the identical
   commands and patterns, then one real deploy for authenticity and the resume.

## Consequences

- **Positive:** zero cost during the long build phase; `destroy`/`apply` practiced without
  fear; identical mental model transfers to cloud; one real deploy at the end.
- **Negative / trade-offs:** LocalStack and `kind` are not 100% identical to real AWS/EKS;
  we will hit a few "works locally, differs in cloud" moments — which is itself a real and
  useful lesson.
- **Follow-ups:** the build-vs-buy register records which local components become managed
  services in the cloud capstone.

## Reversal

Trivially reversible — it is a workflow choice, not a code commitment. We can move to cloud
earlier at any time if a specific lesson requires it.
