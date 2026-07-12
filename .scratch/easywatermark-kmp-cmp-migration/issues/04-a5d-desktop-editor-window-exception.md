# 04 — A5d Desktop editor-window-only exception registry

**What to build:** An explicit **editor-window-only** Phase A exception registry for Desktop: which Android product screens are intentionally absent and why (window entry, AWT file dialogs, no in-app gallery, no About, packaging edge, etc.). **Do not invent** launch/gallery/about product screens on Desktop for matrix symmetry. If owner instead requires full multi-screen Desktop product, stop with a decision package — full nav is out of scope for this ticket unless re-scoped.

**Blocked by:** None — can start immediately (parallel with 01–03).

**Status:** **complete** (S4d-383 / A5d accepted 2026-07-12)  
**Does not complete:** A5 / Phase A / Phase B / §9 DoD (still needs 02–03 owner sign-off + ticket 05).

## Acceptance checklist

- [x] Exception registry table published on this ticket (surface → edge/absent → reason)
- [x] Confirms Desktop already uses shared editor shell/options where claimed; lists remaining edges
- [x] No invented Launch/Gallery/About Desktop UI; no new deps; Android native renderer policy untouched

---

## Phase A product-screen matrix — Desktop

| Surface (Android product) | Desktop Phase A | Classification | Reason / evidence |
|---------------------------|-----------------|---------------|-------------------|
| **Launch screen** | **Absent** | Platform window entry | No-arg `:desktopApp:run` opens `Window` titled `EasyWatermark — Desktop` via `launchDesktopWindow()` (`DesktopWindow.kt`). No `LaunchScreenShell` product route. |
| **Gallery dialog** | **Absent** | File-dialog / drag-drop edge | Image input via AWT `FileDialog` multi-select (`Open image…`) + drag/drop multi-file (`supportedImageFiles` → sequential save). No `GalleryDialogShell`. **Owner 2026-07-12:** Desktop (like iOS) **defaults to system pick**, not in-app gallery — see `docs/parity/v2.10.0/protocol/image-pick-policy.md`. |
| **Editor** | **Present (shared CMP)** | Product route of record | `EditorScreenShell` + shared options (`TextContentOption`, sliders, tile/typeface/style, color, templates sheet, save/share actions). `showPhotoStrip` not Android multi-photo strip — Desktop source is last opened/dropped file(s). |
| **About** | **Absent** | Phase A exception | No `AboutScreenShell` in `desktopApp`. No `AboutViewModel` consumer. Do not invent for matrix symmetry (ticket text). |
| **Share** | **Substitute edge** | OS / file edge | Last real-save file drives share-substitute actions (`SavedOutputActions`); not Android `Intent` share. Preview temp excluded from share state. |
| **Save / export** | **Present (shared + edge)** | Shared actions + AWT | Shared save/preview/status shells; encode via Desktop JPEG/PNG path; destination via `FileDialog` Save / default unique names under user output dir. |
| **Templates** | **Present (shared + edge)** | Product | Shared `EditorTemplateSheetHost` over Room `TemplateRepository` / `TemplateEditor`; locale-aware seed DB under `~/.easywatermark`. |
| **Icon watermark** | **Present (edge + shared)** | Product | Open icon via AWT; `DesktopIconPersistence` to app-private dir; Image-mode render branch. |
| **Permissions / MediaStore** | **N/A** | Desktop FS | No Android storage permission model. |
| **Packaging** | **Edge** | Build | Compose Desktop `nativeDistributions` / `createDistributable`; supported packaging JDK (not Homebrew OpenJDK); unsigned app-image proof only. |

## Shared editor claim (verified by code path)

Desktop production window (`DesktopWindow.kt` / `launchDesktopWindow`):

- Hosts **`EditorScreenShell`** as the window content chrome.
- Options and save UX consume shared composables / use-cases (`WatermarkConfigEditor`, `OutputPrefsEditor`, `TemplateEditor`, shared option shells).
- Raster/composition: Desktop/iOS shared `WatermarkCellComposer` + platform decode/encode — **not** Android native `WatermarkRenderer` (ADR-0004 / S4d-8 / S4d-17 / S4d-190 closed).

## Explicit remaining Desktop edges (not exceptions to invent later)

1. **AWT `FileDialog`** for open image / open icon / save as.
2. **AWT drag-and-drop** file-list flavor for multi-file drop.
3. **Per-user dirs** `~/.easywatermark` (config + templates) and output dir (`~/Pictures` or fallback).
4. **Headless / demo witness** (`Main.kt --headless`, build-local paths) separate from interactive window.
5. **No Navigation Compose multi-screen graph** — single window, editor-only product UX for Phase A.

## What this ticket deliberately does **not** do

- No invented Launch / Gallery / About Desktop screens.
- No new dependencies.
- No Android native renderer policy change.
- No full multi-screen Desktop nav unless owner re-scopes (would need a new ticket).

## Acceptance note

Registry published on this ticket satisfies A5d. Ticket **05** still needs:

- ticket **01** complete (done — `1c049765`),
- tickets **02** + **03** owner-signed exceptions (or production routes),
- then multi-platform closeout gates + explicit A5 PASS/NOT READY.
