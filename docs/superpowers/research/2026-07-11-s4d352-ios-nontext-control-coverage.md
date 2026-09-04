# S4d-352 — iOS production non-text control coverage (read-only)

**Date:** 2026-07-11
**Type:** production control inventory / consumer-first NO-GO (no product code)
**Question:** Are there remaining iOS production editor controls that are still pure SwiftUI and can safely consume an existing shared CMP **non-text** control (or a minimal iosMain host) without inventing a product root and without touching S4d-338 families?

**Verdict: NO-GO — no new safe non-text shared-control candidate.** Interactive non-text editor axes already on shared CMP. Residual SwiftUI is system edges, labels/status, S4d-338 text, or templates (S4d-346). **Not Phase A/B/parity complete.**

**Kimi source review: PASS (confirmed).**
**Verification:** `git diff --check` on this note + plan files; **no build** applies (read-only evidence).

---

## 1. Exact conclusion

1. Production iOS editor **non-text controls** that have matching commonMain APIs are **already** wired via iosMain hosts + Swift `UIViewControllerRepresentable` over `WatermarkWorkflow` callbacks.
2. There is **no smallest GO** pure CMP wire-up that both preserves current iOS behavior and avoids S4d-338 / product invention.
3. **Phase A gate for further iOS CMP control work** remains **S4d-338** (text field / sheet / dialog families) and the **S4d-346 templates sheet** NO-GO — not another discrete non-text host.
4. **Next (S4d-353):** owner-decision pack for the Compose/Skiko alignment blocker — **not** product code.
5. Explicitly: **not** Phase A complete, **not** Phase B, **not** Android v2.10.0 1:1 parity.

---

## 2. Production control / host coverage matrix

Sources: `iosApp/iosApp/ContentView.swift` production body; hosts in `shared/src/iosMain/.../ui/IosSharedComposeHost.kt`; workflow `iosApp/iosApp/WatermarkWorkflow.swift`. DEBUG witnesses (`-sharedComposeWitnesses`) excluded from production counts.

| Production surface | UI owner | Shared API / host | Persistence / system edge |
|---|---|---|---|
| Launch pick | CMP shell | `IosLaunchScreenHost` → `LaunchScreenShell` | `photosPicker` system |
| Icon watermark pick UI | CMP | `IosWatermarkIconOptionHost` → `IconWatermarkOption` | `photosPicker` + `setIconFromBytes` |
| Degree / alpha / text size / H gap / V gap | CMP | `*SliderHost` → `SliderOption` | `WatermarkConfigEditor` via workflow |
| Tile mode | CMP | `IosWatermarkTileModeHost` → `TileMode` | workflow tile write |
| Typeface | CMP | `IosTextTypefaceHost` → `TextTypeface` option | workflow typeface key |
| Fill/Stroke | CMP | `IosTextPaintStyleHost` → `TextPaintStyleOption` | workflow style key |
| Text color swatches | CMP | `IosWatermarkTextColorHost` → `TextColorOption` (`showCustomInput = false`) | workflow color write |
| Watermarked preview | CMP | `IosWatermarkPreviewHost` | Skiko render result PNG |
| Share / Save to Photos row | CMP | `IosSavedOutputActionsHost` → `SavedOutputActions` | UIKit share + Photos save |
| Source re-pick overlay | SwiftUI | — | system `PhotosPicker` |
| Mode caption (`Mode: Text/Image`) | SwiftUI `Text` | none (display only) | mode from config / icon pick |
| Gap caption (`Gaps: H … V …`) | SwiftUI `Text` | none (display only) | values already on sliders |
| Watermark text field + Apply | SwiftUI | blocked | `setWatermarkText` |
| Templates list UI | SwiftUI | blocked drop-in | `IosTemplateBridge` / Room |
| Idle/render/save status copy | SwiftUI | not a drop-in control | workflow state enums |

---

## 3. Remaining pure SwiftUI classification

| Item | Class | Why |
|---|---|---|
| `TextField` + Apply | **S4d-338 NO-GO** | Shared `TextContentOption` uses `ModalBottomSheet` + focused `OutlinedTextField` |
| Templates section | **S4d-346 / S4d-338 NO-GO** | Shared `TemplateListSheet` / host use sheet + `AlertDialog` + `OutlinedTextField`; retain proven SwiftUI |
| Mode / gap captions | Cosmetic **NO-GO** | Not interactive controls; no shared non-text API worth a host |
| `statusView` / `saveStatusView` | Status **NO-GO** | Success string already fed into shared preview; Desktop `SavePreviewStatus` is not a drop-in |
| PhotosPicker edges | **System edge** | Stay native by design |
| Editor / About / Gallery shells | **Product root NO-GO** | Witness-only; inventing production roots is owner product scope |
| Desktop `SaveCommandActions` / export sheet | **N/A** | Not iOS production export shape (PNG + Photos/Share) |

---

## 4. `WatermarkModeActions` non-GO rationale

commonMain `WatermarkModeActions` (`shared/.../ui/compose/WatermarkModeActions.kt`) is a **Desktop** cluster: pick icon + **Use text watermark** + **Preview**, wrapping `IconWatermarkOption`.

iOS production already has **icon pick** on shared CMP. It does **not** expose Use-text / Preview buttons; Image mode comes from icon persistence; text apply path is the separate (blocked) text field. Wiring `WatermarkModeActions` would **invent product UX** (extra buttons; Preview mismatches auto-render iOS), not migrate an existing SwiftUI control. **Consumer-first NO-GO.**

---

## 5. Exact S4d-338 / templates boundaries

**S4d-338 crash families** (do not wire on iOS under current Compose/Skiko mix):

- Material3 `ModalBottomSheet` (missing keyboard-overlap locals)
- Compose `Dialog` / `AlertDialog` (default safe-area / inset paths)
- Focused `OutlinedTextField` (unimplemented text-offset accessor)

**Blocked shared APIs for iOS production:**

- `TextContentOption` / watermark text edit sheet
- `TemplateListSheet` / `EditorTemplateSheetHost` (full sheet surface; hiding Add/Edit is insufficient — S4d-346)
- Any new host that embeds those families

**Safe pattern already in use:** non-text segmented controls, sliders, swatches (`showCustomInput = false`), icon shell, preview, `SavedOutputActions` — workflow owns DataStore/render/system IO.

**Retain until owner alignment:** SwiftUI `TextField` + Apply; SwiftUI Templates section + `IosTemplateBridge`.

---

## 6. Next slice (S4d-353)

**Owner-decision pack** for the Compose/Skiko dependency-alignment blocker that underlies S4d-338 (and thereby templates sheet drop-in).

Out of scope for S4d-353:

- Product code implementing text/templates CMP on iOS
- Inventing iOS About / full editor root / gallery product surfaces
- Dependency version bumps without an explicit owner decision

---

## 7. Verification (this closeout)

| Check | Result |
|---|---|
| Product code | None |
| Build | N/A (read-only) |
| Kimi residual audit | **PASS** (confirmed) |
| `git diff --check` | Clean on staged closeout files |
| Protected untracked `2026-07-11-project-branch-goals-progress.md` | Not staged / not touched |
