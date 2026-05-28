# Conduit — Build vs. Buy Register

The framework, applied to every component. Ask three questions:

1. **Is this our product's core value?**
2. **Can we run it well enough ourselves at our scale?**
3. **What is the cost of getting it wrong?**

High-value / low-risk → **build**. Low-value / high-risk-if-wrong → **delegate**.

The twist for *learning*: we **self-host everything locally** to learn the mechanics for free,
then consciously **swap to managed services** for the cloud capstone — and we can explain why
for each one. That explanation is the interview.

## We BUILD (our product's value + the core learning)

- Application logic (ingest, fan-out, inspector).
- Rate limiting & **load shedding**.
- **Circuit breakers**, retries with exponential backoff, dead-lettering.
- Idempotency & dedup.
- Tenant isolation & RBAC.
- Signature verification & **SSRF protection**.
- Job queue & workers.
- Caching strategy.
- Observability instrumentation.
- Reversible migrations.
- CI/CD pipeline, Kubernetes manifests, NetworkPolicies, Terraform.

## We DELEGATE in production (undifferentiated; someone is better at it)

| Component | Local (to learn) | Production (to ship) | Why delegate |
|---|---|---|---|
| Volumetric DDoS / WAF | n/a (app-layer limits only) | Cloudflare (free tier) | Needs a global scrubbing network + traffic data we cannot replicate |
| TLS certificates | self-signed / mkcert | Let's Encrypt via cert-manager | Automated, free, solved problem |
| Postgres | container, then StatefulSet | Managed Postgres (RDS / DO) | Prod durability, backups, failover, PITR is a full-time job, not our product |
| Redis | container | Managed Redis | Same as above |
| Object storage | LocalStack (S3 API) | S3 / Spaces | Durability at 11 nines is not ours to rebuild |
| Container registry | local | GHCR / managed | Solved infrastructure |
| DNS | /etc/hosts | Managed DNS | Solved infrastructure |

## The lesson

No company builds everything — not even the giants. Knowing to put Cloudflare in front is the
**senior** decision; reinventing DDoS protection badly is the junior one. We build the half
that is our value and learnable; we delegate the half that is undifferentiated and high-risk.
