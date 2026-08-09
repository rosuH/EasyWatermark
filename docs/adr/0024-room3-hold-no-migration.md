# ADR-0024: Room 3 — hold (no migration this cycle)

**Status:** Proposed (2026-08-09)  
**Context slice:** Storage / build toolchain  
**Related:** AGENTS.md Room KMP notes; research `2026-08-08-android17-memory-r8-coroutines.md`

## Context

Room **3.x** appears in the broader Android ecosystem shortlist (API cleanup, KMP alignment, performance). EasyWatermark already runs **Room KMP** for templates:

- Schema **v1** committed (`exportSchema=true`, `shared/schemas/.../1.json`)
- Locale-seeded assets (`ewm-db-ch.db` / `ewm-db-eng.db`)
- Android: framework SupportSQLite + `createFromAsset` compatibility mode (no `sqlite-bundled` on Android)
- Desktop/iOS: `BundledSQLiteDriver` under platform app-data dirs

A Room 3 bump is a **compatibility-critical** store change: seed unpack paths, KMP drivers, and any SupportSQLite vs bundled split must be re-proven on three platforms. This cycle’s approved plan is **Android 17 memory harden + R8 audit**, not a storage migration.

## Decision

1. **Do not** migrate Room artifacts, schema, or builders in this cycle.
2. Keep Room 3 as a **Proposed** follow-up ADR until an owner-scoped upgrade slice exists (single dependency family, rollback HEAD recorded per J4).
3. Any future Room 3 work requires: seed parity tests, Android `createFromAsset` still green, Desktop/iOS BundledSQLite open, and template CRUD smoke on device/simulator.

## Consequences

- **Positive:** no accidental template DB breakage while shipping memory/R8 product work.
- **Trade-off:** delay any Room 3-only APIs/fixes until a dedicated slice.
- **Non-goals now:** schema v2, driver unification, wiping seed strategy.

## Rejected alternatives

- “Drive-by” Room 3 in the memory PR — rejected (scope creep; dual risk surface).
- Dropping Room for DataStore-only templates — rejected (out of product scope).
