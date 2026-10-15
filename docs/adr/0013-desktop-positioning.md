# ADR-0013: Desktop — validation target or shipped product?

**Status:** Proposed (awaiting developer decision; needed by C6, not before)

## Context
Desktop (JVM) arrives nearly free as an architecture-validation target (ADR-0001). Shipping it publicly adds real work: `compose.desktop.nativeDistributions` packaging (dmg/msi/deb), a desktop icon set, macOS signing + notarization, and a distribution/update channel.

## Options
1. **Validation-only (default until decided):** internal builds for engine verification and fast dev loop; no public artifacts.
2. **Shipped product:** add the packaging/signing/notarization tasks to C6 and define a release channel (GitHub Releases?).

## Decision
Pending for **signed / notarized / store-grade** Desktop. Until that lands, Desktop remains validation-first in product scope.

## Amendment — unsigned GitHub Releases channel (2026-10-15)

CREATE (`release.yml` / ADR-0034) attaches unsigned Linux DEB, Windows MSI, and macOS Apple Silicon DMG to the same GitHub Release as the Android APK/AAB. Filenames keep an `UNSIGNED` marker. This is a public download channel only — not notarization, Authenticode, or an auto-update channel. **Build packages** remains the ad-hoc artifacts button. Signing + notarization stay residual.
