# ADR-0034: One-click Android GitHub Release + Play production

**Status:** Proposed  
**Related:** ADR-0031 (Desktop / Build packages stay unsigned and do not mint a store release)

## Context

`release.yml` used to stop at a **draft** GitHub Release. Play upload (`publish.yml`) only ran after a human clicked Publish. That second click never happens on a master merge, and a `GITHUB_TOKEN`-created published release does not start `publish.yml` (Actions will not chain workflow-created `release` events).

## Decision

The manual **Ship Android release** button (`release.yml`, `confirm=CREATE`) is the store ship:

1. Fail closed if tag/release `Apps.versionName` already exists.
2. Build and sign APK/AAB.
3. Publish a GitHub Release (not draft) on the dispatch SHA.
4. Upload that AAB to Play **production** in the same workflow.

`publish.yml` remains for a human Publish of an older draft. Desktop/iOS packages are not this button (ADR-0013 / Xcode Cloud). **Build packages** stays artifacts-only.

## Consequences

- `CREATE` is a one-way door: GitHub Release is public before Play returns. If Play fails, unpublish/yank is a separate owner action.
- The `release` GitHub Environment is the remaining approval latch, if the owner enabled it.
- Bumping `Apps.versionName` is still a code change. The button ships whatever version is on the selected ref.
