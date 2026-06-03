# ADR-0010: Tenant isolation — enforced in the application, org_id from the principal only

- **Status:** Proposed
- **Date:** 2026-06-04
- **Deciders:** Adeel (sole engineer) — proposed for ratification on PR review/merge
- **Context tickets:** CON-8 (API-key auth), CON-9 (event inspector), CON-16 (this write-up)

## Context

Conduit is multi-tenant on the cheapest, most operable shape: **shared tables + an `org_id`
column** on every tenant-scoped row (ADR-0008, `docs/data-model.md`). The `Organization` is the
tenant and is the root of every ownership chain. The make-or-break rule is that one org must
**never** read or write another org's rows — a cross-tenant leak is the worst bug we can ship.

`docs/data-model.md` (the tenant-isolation section) deliberately deferred *how* that rule is
enforced: it named the candidate mechanisms — "a mandatory repository layer that always injects
the filter, and/or Postgres Row-Level Security (RLS) as a database-level backstop" — and promised
"a follow-up ADR" to decide between them. That follow-up was never written. Instead the mechanism
was decided **implicitly in code** when CON-8 added authentication and CON-9 added the first
tenant-scoped read endpoints, and it has been the de-facto pattern for every feature since
(CON-10 destinations/routes, CON-11 sources). This ADR makes the implicit decision explicit and
ratifies the pattern that already ships — it is a write-up of reality, not a new direction.

## Decision

Tenant isolation is enforced **in the application layer**, on two load-bearing rules:

1. **`org_id` is resolved only from the authenticated principal, never from client input.** A
   valid `Authorization: Bearer <key>` is resolved by `ApiKeyAuthenticationFilter` into an
   `ApiKeyPrincipal` (`record ApiKeyPrincipal(UUID orgId, …)`), whose `orgId` is the key's owning
   org (`new ApiKeyPrincipal(key.getOrgId(), …)`). Controllers read the tenant **only** from
   `@AuthenticationPrincipal ApiKeyPrincipal` (e.g. `EventController` uses `principal.orgId()`).
   There is deliberately **no `orgId` request parameter, body field, or path segment** anywhere —
   it is structurally impossible for a client to choose its tenant. (Pinned by a test: a forged
   `GET /events?orgId=<other-org>` is inert and still returns only the caller's rows.)

2. **Every tenant-scoped query filters by that `org_id`.** Isolation lives *in the query*, not in
   a find-then-check-in-Java that would first load another org's row. The repository-method naming
   convention makes the filter visible and uniform across the codebase:
   - `EventRepository.findByIdAndOrgId(id, orgId)`, `findSummariesByOrgId(orgId, …)`,
     `findSummariesByOrgIdAndSourceId(orgId, sourceId, …)` (org filter repeated in the
     `countQuery` so totals stay org-scoped).
   - `SourceRepository.findByIdAndOrgId(...)`, `findByOrgIdOrderByCreatedAtDescIdDesc(...)`.
   - `DestinationRepository.findByIdAndOrgId(...)`, `findByOrgId(...)`, `existsByIdAndOrgId(...)`.
   - `RouteRepository.findByIdAndOrgId(...)`, `findByOrgId(...)`, `findByOrgIdAndSourceId(...)`.

3. **Not-found and not-yours return an identical `404`.** A lookup for a row in another org
   returns empty from the org-scoped query, exactly like a non-existent id, so the handler
   responds with a byte-identical `404` (`{"error":"not_found"}`) in both cases — **never `403`**,
   which would confirm the row exists and act as an existence oracle. See
   `EventController.detail(...)`: `events.findByIdAndOrgId(id, principal.orgId())` mapped to the
   same `404` on `orElseGet`.

4. **This is pinned by cross-tenant integration tests, not just asserted.**
   `EventInspectorIntegrationTest` seeds events for org A and org B and proves: A's key lists only
   A's events; A asking for B's event id and A asking for a random id get **byte-identical 404s**
   that leak nothing about B's payload; a `?sourceId=<B's source>` filter returns an empty page,
   not B's rows; and the forged `?orgId` is ignored. These tests run on the real Postgres via
   Testcontainers and are required by CI, so a regression that drops an `org_id` filter fails the
   build before it can merge.

## Options considered

1. **Postgres Row-Level Security (RLS) as the sole / primary mechanism** — a `USING (org_id =
   current_setting('app.org_id'))` policy enforced by the database, so a forgotten `WHERE` can't
   leak. *Pros:* defense in depth at the lowest layer; resilient to an application bug.
   *Cons / why not now:* it requires setting a per-request session variable (`SET LOCAL app.org_id`)
   on every connection from the pool and trusting that plumbing under virtual threads and
   connection reuse — a new, subtle failure mode of its own; it interacts awkwardly with Flyway
   migrations, the superuser/owner bypass, and JPA's connection handling; and it would still need
   the same application-level tests to prove it. We judged the added moving parts not worth it for
   the current single-Postgres, application-controlled-access deployment. **Credible future
   defense-in-depth:** RLS remains the natural *backstop* to add later (a second lock behind the
   application filter, not a replacement for it); adopting it would be a new ADR that **supersedes**
   this one. *(Rejected as the sole mechanism now; retained as a planned hardening.)*

2. **A separate schema or database per tenant (physical isolation)** — each org gets its own
   schema/DB, so isolation is structural and a stray query can't cross tenants. *Pros:* strongest
   isolation; easy per-tenant backup/export. *Cons / why not:* operational cost grows with every
   org (migrations must fan out across N schemas; connection/pool management multiplies);
   genuinely cross-tenant operational queries (fleet-wide metrics, "all deliveries due now" for the
   delivery worker) become hard or impossible; it scales poorly to many small tenants, which is
   exactly Conduit's expected shape. **Rejected** — the cost is real and ongoing, and shared-table
   isolation tested at the row level meets the requirement.

3. **Trust a client-supplied org id (e.g. an `orgId` query param / body field / header)** — the
   simplest to wire. **Rejected outright: this *is* the leak vector.** Any client could read any
   org's data by changing one value. The tenant must be derived from the authenticated identity and
   nothing else; this is precisely why there is no `orgId` input anywhere in the API.

4. **Application filter, `org_id` from the principal only (CHOSEN)** — the pattern above:
   tenant from `ApiKeyPrincipal`, every query `…AndOrgId`, identical-404 on not-yours, pinned by
   cross-tenant tests. *Pros:* simple, fully testable, one uniform enforcement pattern, no new
   infrastructure. *Cons:* correctness depends on every query remembering the filter — addressed
   under Consequences.

## Consequences

- **Positive:** one enforcement pattern across the whole codebase (`…ByOrgId` / `…AndOrgId`), easy
  to read in review and to grep for; isolation is directly and cheaply **testable** (the
  cross-tenant tests are unmistakable and CI-required); no extra infrastructure, session plumbing,
  or per-tenant operational overhead; the API has no tenant-selection input, so a whole class of
  bypass is structurally impossible.
- **Negative / trade-offs:** correctness relies on **every** tenant-scoped query remembering the
  `org_id` filter — a single forgotten filter is a leak. There is no database-level backstop today,
  so the application is the only line of defense. Mitigated by: (a) the repository-method naming
  convention that makes the filter explicit and uniform (an unfiltered finder stands out in
  review); (b) the requirement that any new tenant-scoped endpoint ship a cross-tenant test as an
  acceptance criterion (security shifted left); and (c) the CI-required Testcontainers tests that
  fail on a regression. An RLS backstop (Option 1) would harden this by catching a missed filter at
  the database, and is the recommended next step if/when the threat surface grows.
- **Follow-ups:** consider adopting RLS as a defense-in-depth backstop (a new superseding ADR);
  keep the "new tenant-scoped endpoint ⇒ cross-tenant test" rule on the ticket checklist; ensure
  the delivery worker and any future background/non-request code paths (which have no
  `ApiKeyPrincipal`) carry the `org_id` explicitly through their queries by the same convention.

## Reversal

ADRs are immutable records of a decision at a point in time — we do not edit an accepted ADR to
change its meaning. If this enforcement approach proves insufficient (most likely: we adopt RLS as
a database-level backstop, or move to physical per-tenant isolation), we write a **new ADR that
supersedes this one**: the new ADR's status references this one (`Supersedes ADR-0010`) and this
ADR is updated only to add `Superseded by ADR-XXXX` in its status line. The code change itself —
adding an RLS policy and session-variable plumbing — is additive and behind the existing
application filter, so it can land incrementally without removing the current, tested guarantee;
there is no risky "rip out and replace" step.
