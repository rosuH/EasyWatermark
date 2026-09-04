# ADR-0013: Desktop — validation target or shipped product?

**Status:** Proposed (awaiting developer decision; needed by C6, not before)

## Context
Desktop (JVM) arrives nearly free as an architecture-validation target (ADR-0001). Shipping it publicly adds real work: `compose.desktop.nativeDistributions` packaging (dmg/msi/deb), a desktop icon set, macOS signing + notarization, and a distribution/update channel.

## Options
1. **Validation-only (default until decided):** internal builds for engine verification and fast dev loop; no public artifacts.
2. **Shipped product:** add the packaging/signing/notarization tasks to C6 and define a release channel (GitHub Releases?).

## Decision
Pending. Until decided, all desktop work is scoped as validation-only.
