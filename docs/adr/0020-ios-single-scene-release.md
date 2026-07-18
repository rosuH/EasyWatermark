# ADR-0020: iOS current release is single-scene

**Status:** Accepted (2026-07-18)  
**Context slice:** issue 13 stage B1 / issue 14  
**Related:** ADR-0017 (shared session ViewModel), issue 13 §7 B1

## Context

The iOS host can structurally open more than one window (`WindowGroup` in `iOSApp.swift`),
and `Info.plist` previously set `UIApplicationSupportsMultipleScenes` to `true`. At the same
time, the product Session graph is process-wide:

- `defaultIosAppServices()` → `IosAppServicesHolder.instance`
- one `WatermarkSessionViewModel`, route, selection, export job state, and temp namespace
- Swift roots (`ContentView`, `WatermarkWorkflow`) resolve the same service graph

Advertising multi-scene support without scene-scoped Session ownership is a false capability
declaration: a second scene would share and corrupt product state.

## Decision

For the **current release**:

1. Set `UIApplicationSceneManifest.UIApplicationSupportsMultipleScenes` to explicit **`false`**.
2. Keep repositories, DataStore, Room, and bridges **process-scoped** (no per-window clone).
3. Keep a permanent fail-closed source guard (`IosSingleSceneManifestTest`) so multi-scene
   cannot return silently.
4. Document the contract in `AGENTS.md` runtime wiring.

Real multi-window support is **out of scope** until after Session ownership work (issue 13
stage E) and requires a separately authorized design with two-scene isolation tests for
route, selection, offsets, export state, temp output, callbacks, and restoration.

## Consequences

- **Positive:** shipped capability matches the architecture that exists; no dual-window
  corruption path via system multi-scene.
- **Positive:** guard fails CI/local `:shared:desktopTest` if the key is re-enabled or moved
  outside the scene manifest.
- **Negative:** iPad multi-window / multi-scene product features remain unavailable until a
  future design.
- **Follow-up:** any future multi-scene enablement must replace the guard with isolation
  tests and an explicit ADR superseding this one — not a silent plist flip.

## Alternatives considered

| Option | Why rejected now |
|---|---|
| Keep multi-scene `true` and document “use one window only” | Capability still false; OS may create a second scene |
| Implement scene-scoped Session in B1 | Larger ownership rewrite (stage E); not a firewall slice |
| Remove `WindowGroup` / rewrite Swift host | Unrelated churn; single-scene is already expressible via the manifest |
