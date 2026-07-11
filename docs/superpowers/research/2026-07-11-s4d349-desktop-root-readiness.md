# S4d-349 — Desktop A2 root readiness (read-only)

**Date:** 2026-07-11
**Type:** consumer-first ready / no-go (no code)
**Question:** Does Desktop need a further A2 “shared root” migration for Launch / Gallery / About, or is the production editor root already complete?

**Verdict: NO-GO (consumer-first)** — inventing Desktop Launch/Gallery/About would create product surfaces that do not exist.

**Kimi source review: PASS (corroborated).**
**Verification:** `git diff --check` on this note; **no build** applies (read-only evidence).

---

## 1. Exact conclusion

1. Desktop production UI path is exactly:
   `Main.kt` `main()` → (no `--headless`) → `launchDesktopWindow()` → `DesktopWindow` editor.
2. That window **already** consumes commonMain `EditorScreenShell` and shared primitives (preview frame, sliders/options, templates host, save options, `SavedOutputActions`, theme).
3. Desktop has **no product Launch**, **no Gallery**, **no About** roots. Source acquisition is AWT `FileDialog` / drag-drop at the editor edge.
4. `--headless` is **non-UI automation** (`runHeadless` + `DesktopWatermarkFlow`); not a product screen root.
5. Adding Launch/Gallery/About on Desktop would **invent** product surfaces without a production consumer gap — violates consumer-first migration.
6. **S4d-338 is not relevant** to this Desktop root decision (iOS CMP text/sheet/dialog only).
7. **Next:** A1 residual wrapper assessment (Android), not A2 root invention. Not Phase A/B/parity complete.

---

## 2. Production vs non-product inventory

| Surface | Desktop production? | Evidence |
|---|---|---|
| Entry | Yes — window | `Main.kt` default branch → `launchDesktopWindow()` |
| Editor root | Yes — shared shell | `DesktopWindow.kt` → `EditorScreenShell` |
| Preview / controls / templates / output | Yes — shared components | Imports and call sites in `DesktopWindow.kt` |
| Launch product screen | **No** | No `LaunchScreenShell` production use |
| Gallery multi-select product screen | **No** | Multi-file via Open/drop, not gallery UI |
| About product screen | **No** | No About route/root |
| `--headless` | Automation only | `Main.kt` `runHeadless` — exits after witnesses |

---

## 3. Source evidence (key paths)

| Path | Role |
|---|---|
| `desktopApp/.../Main.kt` | `main`: window vs `--headless` |
| `desktopApp/.../DesktopWindow.kt` | `launchDesktopWindow` / `EditorScreenShell` + shared controls |
| `desktopApp/.../DesktopWatermarkFlow.kt` | Shared save/render spine for window + headless |
| A0 matrix | Aligns: Desktop editor shared root; no launch/gallery/about |

---

## 4. Platform edges (remain Desktop)

- AWT `FileDialog` open / multi-select / save-as
- Drag-and-drop file list
- User dirs (`~/.easywatermark`, Pictures/output, icon persistence)
- Skiko render path (not Android native)
- Packaging / distributable (unsigned app image)
- Share substitute: folder open + clipboard path via `SavedOutputActions`

---

## 5. S4d-338

**Not applicable** to Desktop A2 root readiness. S4d-338 is an iOS Compose/Skiko runtime block for text/sheet/dialog CMP APIs.

---

## 6. Next lane

| Do | Do not |
|---|---|
| **A1 residual Android wrapper assessment** | Schedule Desktop Launch/Gallery/About implementation without product decision |
| Keep thinning only real wrappers with zero product loss | Treat `--headless` as missing product UI |
| Optional later owner product decision for Desktop About/empty-state | Claim A2 incomplete for lack of Android Nav graph |

---

## 7. Overclaim guard

- Not Phase A complete
- Not Phase B / 1:1 parity
- Desktop editor shared root **already landed**; NO-GO is for inventing additional roots

---

*End of S4d-349 readiness note*
