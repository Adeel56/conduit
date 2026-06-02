# CI Security Scanning (Trivy + CodeQL)

Automated gates that turn "shift security left" into a merge requirement (CON-5). Two scanners run
on every PR into `main`, as separate parallel jobs so they don't slow build/test.

## What each scanner covers

| Scanner | Looks at | Catches | Where it reports |
|---|---|---|---|
| **Trivy** (`.github/workflows/ci.yml`, `trivy-image-scan` job) | the built **Docker image** — OS packages of the distroless base **and** the app's bundled JAR dependencies | known **CVEs** in things we *pulled in* (vulnerable/compromised deps, stale base layer) | job logs (gate) + Security tab (SARIF) |
| **CodeQL** (`.github/workflows/codeql.yml`) | **our source code** | security **bug patterns** we *wrote* — injection, unsafe deserialization, path traversal, SSRF | Security tab (code scanning alerts) |

They cover the supply-chain and code-security rows of [`threat-model.md`](threat-model.md).

## How a finding blocks a merge

- **Trivy** fails its job (exit 1) on a **fixable HIGH/CRITICAL** CVE → red check.
- **CodeQL** raises code-scanning alerts; PR blocking is governed by branch-protection required
  checks + the repo's code-scanning merge protection (CodeQL has no "fail the build" YAML knob).

Once these checks are added to the `protect-main` ruleset as **required status checks**, a red
check disables the merge button.

### Recommended required checks (wire after the first green run)

Make **both** `Trivy image scan` and `CodeQL analyze (java-kotlin)` required on `protect-main`, in
this order (register a check only **after** it has run green once, so the real name exists to select):

1. **`Trivy image scan`** can be required immediately — its gate works on this private repo even
   with code scanning off (the SARIF upload is best-effort; see Notes).
2. **`CodeQL analyze (java-kotlin)`** — defer until it goes green. On a **private** repo that means
   **first enabling code scanning / GitHub Advanced Security (or making the repo public)**; until
   then CodeQL's analyze step fails at upload (see Notes), so making it required before that would
   block every merge on a 403. Enable GHAS → confirm a green CodeQL run → then add it as required.

## Triaging a finding

**Trivy — a HIGH/CRITICAL fails the build.** Preferred fix first, allowlist only as a last resort:

1. **Fix it.** Bump the offending dependency, or update the distroless base-image digest in
   [`Dockerfile`](../../Dockerfile) to a freshly rebuilt tag (that base is pinned in the Dockerfile
   only — the Trivy gate scans the image built from it). Re-run; gate clears.
2. **Accept it (reviewed).** If there's genuinely no fix yet, or it's not reachable, add a line to
   [`.trivyignore`](../../.trivyignore) **with justification, owner, and an `exp:` expiry**. This is
   a security decision on a ticket — not a silent mute. Deleting the line re-flags the CVE.

> The gate runs with `ignore-unfixed: true`: CVEs **with no available fix** (common in a distroless
> Debian base we don't control) don't fail the build — they'd be un-actionable noise. They still
> appear in the Security-tab SARIF. Only *fixable* HIGH/CRITICAL gate the merge.

**CodeQL — an alert appears.** Open it in the Security tab, confirm it's a true positive, and fix
the code. Dismiss (with a reason) only for a justified false positive.

## Notes / caveats

- **Private repo:** uploading SARIF to the Security tab needs code scanning / GitHub Advanced
  Security enabled (Settings → Code security). If it's off, CodeQL's analyze step fails at upload,
  and Trivy's upload step no-ops (it's `continue-on-error`, so the **gate still works**). Enable it,
  or make the repo public, to get the Security-tab view.
- **Supply chain:** every action is pinned by full commit SHA (tags are mutable, and
  `aquasecurity/trivy-action` tags were hijacked in March 2026). A natural follow-up is Dependabot
  (`github-actions` ecosystem) so the pins are kept fresh rather than silently rotting.
- **Trivy blind spot:** the distroless JRE is laid down as files, not an OS package, so Trivy won't
  enumerate JDK-level CVEs in the base. It does scan the app JAR's dependencies (most of the real
  risk). This complements, not replaces, dependency scanning.
- **Transient failures:** the Trivy job builds the image (pulling base images + resolving Maven
  deps), so a registry or Maven-Central blip can fail the gate spuriously. **Re-run the job first**
  before treating a red Trivy gate as a real finding.
