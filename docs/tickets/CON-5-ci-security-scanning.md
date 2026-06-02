# CON-5 — Security scanning in CI (Trivy image scan + CodeQL code scan)

**Type:** chore (hardening / security) · **Branch:** `chore/CON-5-ci-security-scanning`

## Goal (value)

Turn the "shift security left" principle into automated CI gates. Today the CI workflow has an
explicit placeholder for security scans. This ticket wires them in so a known-vulnerable
dependency/image or a risky code pattern **fails the build** instead of shipping.

## Concepts

- **Trivy** scans the built **Docker image** (and dependencies) for known CVEs in OS packages and
  libraries. Partial defense against pulling in a vulnerable/compromised dependency.
- **CodeQL** (GitHub-native, free for this repo) scans **source code** for security bug patterns
  (injection, unsafe deserialization, etc.). Both report into the PR.

Together they cover the supply-chain + code-security rows of `docs/security/threat-model.md`.

## Acceptance criteria

- [ ] **CodeQL**: add GitHub's CodeQL analysis workflow for Java, running on PRs to `main`
      (use the official `github/codeql-action`). Findings surface on the PR / Security tab.
- [ ] **Trivy**: build the image in CI and scan it with Trivy (official `aquasecurity/trivy-action`),
      failing the job on unaddressed **HIGH/CRITICAL** vulnerabilities (with a documented way to
      allowlist a specific accepted CVE if ever needed).
- [ ] Scans run on pull requests into `main`; keep CI time reasonable (scan can be a separate job
      so it runs in parallel with build/test).
- [ ] Document (README or a SECURITY note) what the scans do and how to triage a finding.
- [ ] Decide and document: which scan results become **required** status checks on `protect-main`
      (CodeQL and/or Trivy) — wire the required check after the workflow has run once.

## Security criteria

- [ ] Workflows use least-privilege `permissions` (CodeQL needs `security-events: write`; keep the
      rest read-only).
- [ ] Actions pinned to a trusted version/SHA; no unverified third-party actions.

## Forward plan

1. Add CodeQL workflow (Java, autobuild or `./mvnw` build).
2. Add a Trivy job: build image, scan, fail on HIGH/CRITICAL.
3. Run once on a PR; confirm findings surface correctly.
4. Add chosen scans as required status checks on `protect-main`.
5. Document triage in README/SECURITY.

## Reverse plan (matched to change type)

- **CI workflow change (additive)** → delete the workflow files; remove the required-status-check
  entries from the `protect-main` ruleset. Cheap and clean.
- No application code, schema, or config changes.

## Verification

- **Worked:** scans run on a PR; a deliberately introduced vulnerable dep/pattern would fail the
  build (optionally test this once on a throwaway branch); findings appear in the Security tab.
- **Failed (detect):** scans don't run, or pass trivially without actually analyzing — review the
  workflow logs to confirm real analysis occurred.

## Blast radius

CI-only, additive. Worst case: a noisy/false-positive finding blocks a merge — handled via the
documented allowlist/triage path. No production impact.

## Notes for the agent

Follow `CLAUDE.md` and `docs/security/security-baseline.md`. Use official actions, pinned, with
least-privilege permissions. Keep build+test and scanning as separate parallel jobs so feedback
stays fast. Explain what each scanner checks and how a finding is triaged. Do not push to main;
stop after pushing the branch for PR review.
