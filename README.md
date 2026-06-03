# Conduit

A self-hostable service that reliably **receives** third-party webhooks, lets you
**inspect** them, and **delivers** them to your own endpoints with retries and replay.

> **Working name.** Teams rename things constantly. If a better name appears, we change it
> in one ADR and move on.

---

## Why this repo looks the way it does

This is not just an app. It is a **deliberate simulation of how a real product is built,
shipped, and operated** — from the first idea, through parallel feature work, to production
and on-call. The code is the *vehicle*; the engineering process is the *cargo*.

If you are reading this as an interviewer: the `/docs` folder is the point. It shows the
*decisions* and *why*, not just the result.

## The arc we are running (compressed ~2-year product lifecycle)

1. **Inception** — problem brief, threat model, scope boundary, first decisions.
2. **Design** — architecture, data model, API contract, ADRs.
3. **Foundation** — repo, branching, CI skeleton, local docker-compose.
4. **Build sprints** — features as tickets → branches → PRs → review → merge. ← *we are here*
5. **Hardening** — load tests, security pass, rollback drills, staging.
6. **Launch** — release strategy, Terraform apply, Kubernetes.
7. **Operate** — observability, on-call simulation, injected incident + postmortem.
8. **Learn** — retro, feedback into the backlog.

## Documentation map

| Folder | What lives here |
|---|---|
| `docs/adr/` | Architecture Decision Records — *what* we decided and *why*, including rejected options. |
| `docs/security/` | Threat model, security baseline, container/runtime hardening checklist. |
| `docs/decisions/` | Build-vs-buy register — what we build vs. delegate, and why. |
| `docs/templates/` | The change/reversal template every ticket uses. |
| `docs/runbooks/` | Operational docs: how to deploy, how to roll back, how to recover. |

## Running locally

The Build stage is underway (CON-3 → CON-12). Past the original walking skeleton (a Spring Boot
app + Postgres + `/health`), the app now **receives** webhooks (`POST /ingest/{ingestKey}` stores
an immutable Event), lets you **inspect** stored events (tenant-scoped list/view), authenticates
callers with **API keys**, and manages **organizations, sources, destinations, and routes** — the
wiring that delivery (a later ticket) will run on. It is still self-hosted, containerized, and CI-gated.

**Prerequisites:** Docker + Docker Compose. (For running the app outside a container you also need
JDK 21 — Temurin via SDKMAN, per ADR-0006.)

```bash
cp .env.example .env          # then edit POSTGRES_PASSWORD
docker compose up --build     # starts Postgres, a Redis stub, and the app

curl localhost:8080/health    # -> {"status":"UP"}  (200; 503 if the DB is down)

docker compose down           # stop;  add -v to also wipe the Postgres volume
```

Or run just the dependencies in containers and the app from source on the host:

```bash
docker compose up -d postgres
./mvnw spring-boot:run         # reads SPRING_DATASOURCE_* from your environment / .env
```

Run the tests with `./mvnw verify`.

## Core principles (non-negotiable, cross-cutting)

- **Reversibility.** Every change documents how to undo it, matched to the change type.
- **Least privilege, everywhere.** OS user, container, pod, network, cloud IAM, app RBAC.
- **Security is shifted left.** Threats identified in Inception; controls distributed per stage.
- **Build vs. buy on purpose.** Build our product's value; delegate undifferentiated heavy lifting.
- **Observability is built-in, not bolted-on.**
- **Scope is cut deliberately.** What we *don't* build is written down.

See `docs/adr/0001-build-conduit.md` for where it all starts.
