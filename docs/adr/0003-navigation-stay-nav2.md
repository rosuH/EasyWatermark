# ADR-0003: Navigation — stay on Nav2 via the JetBrains coordinate; defer Nav3

**Status:** Accepted (2026-06-13) · **Plan ref:** D3

## Context
Navigation 3's runtime is KMP-stable but its UI artifact (`NavDisplay`) is Android-only; the CMP mirror is 1.0.0-alpha06. Google's own guide calls Nav2→Nav3 an atomic rewrite and excludes deep links. androidx navigation-compose is in maintenance mode but fully serves this app's simple graph.

## Decision
Keep Nav2. In commonMain use `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` (mirror lags androidx 2.9.7 — slight downgrade, test at swap). Convert all routes to `@Serializable` types now. Re-evaluate Nav3 at C6 when its CMP UI artifact stabilizes (official `navigation-3` skill is installed for that day).

## Consequences
- No navigation rewrite risk during the migration; one coordinate swap instead.
- `@Serializable` routes also remove Parcelize from common-bound code (ADR-0007).
