# ADR-0031: Desktop packaging CI is not a PR required check

**Status:** Proposed  
**Related:** ADR-0013 (Desktop positioning — **untouched**; this ADR is CI policy only), ADR-0012 (docs-with-code / CI as verifier surface)

`desktop_packaging.yml` ran as a PR required check with a `dorny/paths-filter` classifier plus a `desktop-packaging-gate` aggregator so path-filtered skips would still report a stable status. `dorny/paths-filter` downloads from `codeload.github.com` at Set up job and intermittently 429/502s, failing the always-on required-check gate on SHAs whose product checks are green.

**Decision:** The PR merge gate is **PR Checks only** (`pr_pre_check.yml`: `assembleDebug`, `:shared:desktopTest`, `:app:testDebugUnitTest`, iOS host build, plus `:desktopApp:classes`). Unsigned 3-OS Desktop packaging (`desktop_packaging.yml` jpackage matrix) runs on weekly `schedule`, `push` to `master` with native `on.push.paths`, and as a reusable workflow from **Build packages** (`build_packages.yml`). The manual Run button is Build packages (platform checkboxes; optional signed Android APK/AAB; no GitHub Release). It is **not** a PR required check and has no `pull_request` trigger. CI must not `uses:` third-party path-classifier Actions (`dorny/paths-filter` class) whose codeload download is a per-run failure point. Native `on.push.paths` is safe here because a filtered-out push simply does not start the workflow.

This matches the 2026-08-18 owner-accepted peer research (Cindy, LocalSend, RustDesk): peers gate PRs on compile/test, not installers. `:desktopApp:classes` is the remaining PR-time desktop net — a compile check, not an installer.

ADR-0013 (Desktop as validation target vs shipped product) is **untouched**. This ADR does not decide whether Desktop is a public product, only when unsigned CI installers run.

## Considered and rejected

- Keep a non-required PR packaging smoke — owner accepted removing the PR trigger entirely (fable5 judgment call 1).  
- New PR job for `:desktopApp:classes` — owner accepted adding the step inside the existing required `build` job (fable5 judgment call 2).  
- Vendor `dorny/paths-filter`, add `tj-actions/changed-files`, or a first-party git-diff classifier — all still exist only to make a path-classified workflow safe as a required check; that constraint no longer applies.  
- `on.pull_request.paths` — would recreate the Pending trap if anyone re-lists the workflow as required.

## Consequences

- Owner must remove `Desktop packaging (required-check gate)` (and any leftover `Classify packaging relevance` / `Unsigned linux-deb` / `Unsigned windows-msi` / `Unsigned macos-arm64-dmg` entries) from the `master` branch protection / ruleset **before or together with** merging this workflow change. YAML alone is not enough; skipped ruleset edit leaves every PR Pending forever.  
- Irrelevant `master` pushes do not start `desktop_packaging.yml`. Weekly schedule still runs the full 3-OS matrix. Manual dispatch is **Build packages** (pick platforms; Android artifacts do not mint a tag or draft).  
- Artifacts remain unsigned, short-lived, and not notarized — packaging honesty comments are unchanged.
- `pr_pre_check.yml` must **not** use `on.pull_request.paths-ignore`. Required check names (`Compile + migration safety net…`, `iOS simulator tests + host build (macos)`) are created only after a workflow run starts. A first-party `git diff` classifier skips Gradle/Xcode on docs/assets-only PRs; the same job names still report success. Classify failure is fail-closed (run the product jobs). Do not add `dorny/paths-filter`.
