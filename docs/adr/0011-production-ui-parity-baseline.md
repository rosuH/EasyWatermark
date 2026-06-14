# ADR-0011: Production v2.10.0 is the UI parity baseline

**Status:** Accepted (2026-06-13) · **Plan ref:** Goal 2, C1.10

## Context
Developer goal: "UI fully aligned with the production version." The current Compose branch contains interim screens that drifted from production (e.g., simplified EditorScreen layouts). The latest release is **v2.10.0** (2025-10-26) — note the branch's `versionName 2.9.6` is stale.

## Decision
The visual/behavioral source of truth is the v2.10.0 release build (master). Current Compose screens are drafts to be corrected toward it. Enforcement: (a) C1.10 UI-parity audit produces a per-screen deviation backlog (production vs Compose screenshots on the same emulator); (b) every layout migration captures a production baseline first (migrate-xml skill Step 4); (c) deviations ship only with an ADR or explicit sign-off.

## Consequences
- Parity also resolves smaller calls by default: Palette background stays, pinch-to-scale stays off, quality snapping stays (ADR-0014).
- The audit needs production APK + debug build side-by-side (different applicationIds — they coexist).
