# CON-6 — Remediate scanner findings (fix fixable HIGH/CRITICAL, document the rest)

**Type:** chore (security remediation) · **Branch:** continue on `chore/CON-5-ci-security-scanning`
(so CON-5 merges green) — or a `fix/CON-6-...` branch off CON-5 if preferred.

## Goal (value)

The CON-5 Trivy gate, on first run, correctly flagged 7 fixable HIGH/CRITICAL CVEs in the image
(real CVEs disclosed after Spring Boot 3.5.14 shipped). Remediate them so the gate goes green and
`main` carries no known-vulnerable dependencies. This is the triage exercise the security baseline
exists to produce.

## The findings to address

| Package | Installed → Fixed | Severity | Action |
|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` (transitive via web starter) | 10.1.54 → 10.1.55 | 3 CRITICAL + 3 HIGH | **Fix** — override `<tomcat.version>` |
| `org.postgresql:postgresql` (JDBC driver) | 42.7.10 → 42.7.11 | 1 HIGH | **Fix** — override `<postgresql.version>` |
| `liblcms2-2` (distroless base OS package) | 2.14-2 → 2.14-2+deb12u1 | 1 HIGH | **Accept (time-boxed)** if no rebuilt base image exists yet — `.trivyignore` with justification + expiry |

## Acceptance criteria

- [ ] In `pom.xml` `<properties>`, override the BOM-managed versions to the patched releases:
      `<tomcat.version>10.1.55</tomcat.version>` and `<postgresql.version>42.7.11</postgresql.version>`
      — **verify both versions exist on Maven Central first** (CLAUDE.md rule).
- [ ] Add a comment above each override explaining *why* (CVE IDs) and the **exit condition**:
      remove the override once Spring Boot's BOM catches up (≥ the release that pins the fix).
- [ ] `./mvnw verify` stays green (the override is a patch bump; integration test still passes).
- [ ] Rebuild the image and re-scan locally: the 6 Tomcat + 1 Postgres findings are **gone**.
- [ ] For `liblcms2`: check whether a rebuilt `distroless/java21-debian12:nonroot` digest with the
      fix is available. If yes, bump the digest in the `Dockerfile` (and keep it in sync with
      `docker-compose.yml`'s postgres pin discipline). If **not**, add a single `.trivyignore` entry
      for that exact CVE with justification ("base-OS package, no fixed distroless image published
      yet, not reachable from app code"), an owner, and an `exp:` expiry ~30–60 days out.
- [ ] The Trivy gate passes locally after remediation (`exit 0`).

## Security criteria

- [ ] Overridden versions verified against Maven Central (no typo'd/nonexistent version).
- [ ] Any `.trivyignore` entry is justified, owned, and time-boxed — never a silent mute.

## Forward plan

1. Verify 10.1.55 and 42.7.11 exist on Maven Central.
2. Add the two version-override properties to `pom.xml` with explanatory comments + exit condition.
3. `./mvnw verify` → green.
4. Rebuild image, re-scan with Trivy → confirm the 7 fixable findings drop to (at most) the 1
   base-OS CVE.
5. For liblcms2: bump the distroless digest if a fixed one exists; else add the time-boxed
   `.trivyignore` entry.
6. Re-scan → gate exits 0.

## Reverse plan (matched to change type)

- **Dependency version override (config in pom.xml)** → remove the two property lines; reverts to
  BOM-managed versions. Cheap. (We *want* to remove them later anyway, once the BOM catches up —
  that removal is tracked by the exit-condition comment.)
- **`.trivyignore` entry** → delete the line; the CVE re-flags on the next scan.
- **Dockerfile digest bump (if done)** → revert to the previous digest.

## Verification

- **Worked:** `./mvnw verify` green; local Trivy gate `exit 0`; CI Trivy check green on the PR.
- **Failed (detect):** verify fails (bad version), or the gate still red (a finding missed).

## Blast radius

Low. Patch-level version bumps within the same minor line (security/bug fixes, no breaking API).
The integration test (full app boot) is the safety net — if a bump broke wiring, it goes red.

## Notes for the agent

Verify the two versions on Maven Central before adding. Keep the change minimal — two property
lines + comments, plus possibly one `.trivyignore` line or a digest bump. Re-scan locally and show
the before/after finding counts. Explain the override mechanism and the exit condition. Do not push
to main; this rides on the CON-5 branch so CON-5 merges green.
