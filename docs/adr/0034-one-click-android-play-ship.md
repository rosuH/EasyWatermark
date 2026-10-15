# ADR-0034: One-click GitHub Release (Android + unsigned Desktop) + Play production

**Status:** Proposed  
**Related:** ADR-0031 (Desktop packaging unsigned; not a PR required check), ADR-0013 (Desktop positioning)

## Context

`release.yml` used to stop at a **draft** GitHub Release. Play upload (`publish.yml`) only ran after a human clicked Publish. That second click never happens on a master merge, and a `GITHUB_TOKEN`-created published release does not start `publish.yml` (Actions will not chain workflow-created `release` events).

CREATE also used to attach **Android only**. Desktop installers lived on the separate **Build packages** button (artifacts-only), so GitHub Releases never shipped Desktop downloads.

## Decision

The manual **Ship release** button (`release.yml`) is the store + GitHub ship. `confirm` is one of:

**CREATE** (new version):

1. Fail closed if tag/release `Apps.versionName` already exists.
2. Build and sign Android APK/AAB **in parallel with** unsigned Desktop installers (Linux DEB, Windows MSI, macOS arm64 DMG) via `desktop_packaging.yml`.
3. Publish a GitHub Release (not draft) on the dispatch SHA with Android assets **and** the three Desktop installers (filenames keep the `UNSIGNED` marker — not notarized / Authenticode).
4. Upload that AAB to Play **production** in the same workflow.

CREATE fails closed if any Desktop OS lane is missing; it must not publish an Android-only Release when Desktop was expected.

**PLAY** (resume): require that tag/release to already exist and be published (HTTP 200, not draft). Download its AAB + `mapping.txt` and upload Play production. Does not mint a tag, rebuild Android, or rebuild Desktop.

`action-gh-release` `files:` must list each asset once. Overlapping globs (`*-signed.apk` plus `*.apk`) upload the same APK twice; the second call PATCHes the asset and fails (`update-a-release-asset` 404) after the Release is already public.

`publish.yml` remains for a human Publish of an older draft. iOS IPA stays on Xcode Cloud. **Build packages** stays the ad-hoc artifacts-only button (no tag / Release / Play).

## Consequences

- `CREATE` is a one-way door: GitHub Release is public before Play returns. If Play does not run, use `confirm=PLAY` on the same workflow (same `Apps.versionName`). Unpublish/yank is still a separate owner action.
- CREATE wall-clock grows with the Desktop matrix (parallel with Android; typically dominated by the slowest OS lane).
- The `release` GitHub Environment is the remaining approval latch, if the owner enabled it.
- Bumping `Apps.versionName` is still a code change. The button ships or resumes whatever version is on the selected ref.
- Desktop on GitHub Releases is an **unsigned** public download channel; macOS/Windows trust UI will warn. Signed/notarized Desktop remains residual (ADR-0013).
