# S4d-351 — A1 Android wrapper residual closeout (read-only)

**Date:** 2026-07-11  
**Type:** A1 residual inventory + closeout (no product code)  
**Question:** After S4d-348 (About) and S4d-350 (SaveExport), are there any remaining **safe pure A1 adapters** — public Android wrappers that only resolve resources/slots into already-shared shells?

**Verdict: A1 thinning exhausted.** No further pure-adapter candidates. **Not Phase A complete.**

**Kimi residual audit: PASS (confirmed).**  
**Verification:** `git diff --check` on this note + plan files; **no build** applies (read-only evidence).

---

## 1. Exact conclusion

1. **A1 pure-adapter thinning is closed.** The only remaining safe pure adapters were About and SaveExport; both are already inlined as private `ComposeMainActivity` helpers over commonMain shells.
2. Remaining Android `ui/**` surfaces own **real platform edges** (permissions, Coil/`Uri`, native `WatermarkRenderer`, pickers, dynamic color, `FuncTitleModel` resources, MediaStore/export IO). Collapsing them is **not** A1 pure thinning — it would move ownership or invent shared abstractions without a dual-consumer gain.
3. **OpenSource** and **Recovery** are already commonMain screens with string/callback injection at the Activity edge — nothing left to delete as a public pure wrapper.
4. **A1 closeout meaning:** stop scheduling pure Android wrapper inlines; residual Android work is edge-owner cleanup (non-A1) only when a named consumer-first slice appears.
5. **Next (S4d-352):** assess **real iOS production non-text controls** versus shared CMP — not a new product root, not S4d-338 text/sheet/dialog rework.
6. Explicitly: **this is not Phase A complete**, not Phase B, not Android v2.10.0 1:1 parity.

---

## 2. Why About + SaveExport were the final safe pure adapters

| Slice | Before | After | Why pure A1 |
|---|---|---|---|
| S4d-348 About | public `ui/about/AboutScreen.kt` | private `AboutScreenAndroid` in `ComposeMainActivity` | Only resources, hard-coded labels/URLs, `AboutViewModel` toggles, logo `AndroidView` slot → `AboutScreenShell` |
| S4d-350 SaveExport | public `ui/save/SaveExportSheet.kt` | private `SaveExportSheetAndroid` in `ComposeMainActivity` | Only `stringResource` map + Coil `Uri` thumbnails → `SaveExportSheetShell`; export/permission/share stay on Activity |

Pattern: **file-private activity helper + shared shell**, delete the public app wrapper package file. No route/dependency/VM/persistence/renderer/permission semantics change.

Further public wrappers either **are not pure** (own edges) or **are already shared** (OpenSource/Recovery).

---

## 3. Remaining Android wrappers / edges (not pure A1)

| Surface | Path | Shared shell/component | Real edge (why not A1) |
|---|---|---|---|
| Launch | `ui/LaunchScreen.kt` | `LaunchScreenShell` | Permission request (`READ_MEDIA_*` / storage), logo `ColoredImageVIew` / painters |
| Gallery | `ui/compose/GalleryDialog.kt` | `GalleryDialogShell` + grid/FAB/top bar | `BackHandler`, Coil/`Uri`, picker/dismiss contracts |
| Editor | `ui/EditorScreen.kt` | `EditorScreenShell`, top bar, strip, bottom controls, template host | Native `WaterMarkCanvas` / `WatermarkRenderer`, Coil strip, `FuncTitleModel` options, templates CRUD wiring |
| Icon option | `ui/compose/IconOption.kt` | `IconWatermarkOption` | Permission + `PickVisualMedia` + `Uri→MediaRef` + Coil preview |
| Color option | `ui/compose/ColorStyleOption.kt` | (Android MotionLayout/resource path; shared `TextColorOption` is Desktop/iOS) | Android resources + constraintlayout MotionLayout edge |
| Theme | `ui/Theme.kt` | commonMain `AppTheme` non-dynamic | Dynamic color / `DynamicColorCapability` Android actual |
| About (done) | private in `ComposeMainActivity` | `AboutScreenShell` | Already thinned |
| Save export (done) | private in `ComposeMainActivity` | `SaveExportSheetShell` | Already thinned; MediaStore/share/open stay Activity |
| OpenSource | commonMain + Activity route | `OpenSourceScreen` | Edge = strings + `onOpenLink` only — no public pure wrapper left |
| Recovery | commonMain + Activity gate | `RecoveryScreen` | Edge = strings + clipboard/close — no public pure wrapper left |
| Activity IO | `ComposeMainActivity` | — | Share-in, export permission, MediaStore, share-out, open gallery, crash recovery gate |
| VM | `MainViewModel` / `AboutViewModel` | shared editors already extracted | Android IO/render/UI state (S4d-191) |

**Do not** schedule A1 “inline Launch/Gallery/Editor/Icon/Color/Theme” as pure adapter work.

---

## 4. Direct commonMain consumers (context for residual Phase A)

Production consumers of shared UI (not exhaustive; A0 matrix is authoritative):

- **Android:** Nav roots over launch/gallery/editor/about/save/open-source/recovery shells; editor controls; native renderer stays Android-only.
- **Desktop:** `DesktopWindow` → `EditorScreenShell` + shared options/templates/save actions (no Launch/Gallery/About product roots — S4d-349 NO-GO).
- **iOS:** production `LaunchScreenShell`, discrete shared controls (tile/style/typeface/sliders/color/icon), preview frame, `SavedOutputActions`; **no** production About (S4d-347 NO-GO); **no** shared templates sheet (S4d-346 NO-GO); text field / sheet / dialog **S4d-338 blocked**.

---

## 5. A1 closeout meaning (durable)

- **A1 done** = pure public Android wrappers that only adapt into shared shells are gone or already inlined; residual surfaces are **edge owners**.
- **A1 done ≠ Phase A complete.** Phase A still has consumer-first residual work (e.g. iOS non-text CMP assessment, optional non-A1 edge hygiene) under codex-goal residual order after A0.
- Residual order reminder (A0): A1 (closed) → A2 optional Desktop roots (**NO-GO invent**, S4d-349) → A3 iOS non-text/non-sheet → A4 only if dual consumer → A5.

---

## 6. Next slice (S4d-352)

**Assess real iOS production non-text controls versus shared CMP.**

In scope:

- Inventory current iOS production UI that is still SwiftUI (or not shared CMP) and is **not** focused CMP text / ModalBottomSheet / Dialog.
- Map each to an existing commonMain control or document a genuine gap.
- Consumer-first only; production hosts only (not DEBUG witnesses).

Out of scope for S4d-352:

- Inventing a new iOS product root (About, gallery multi-select screen, full editor root shell).
- Touching S4d-338 families: `ModalBottomSheet`, Compose `Dialog`/`AlertDialog`, focused `OutlinedTextField` / watermark text sheet.
- Android pure wrapper inlines (A1 closed).
- Phase A/B/parity completion claims.

---

## 7. Verification (this closeout)

| Check | Result |
|---|---|
| Product code | None |
| Build | N/A (read-only audit) |
| Kimi residual audit | **PASS** (confirmed by coordinator) |
| `git diff --check` | Clean on staged closeout files |
| Protected untracked `2026-07-11-project-branch-goals-progress.md` | Not staged / not touched |
