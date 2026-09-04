# iOS / Desktop alignment to signed Android baseline (B3 / ticket 09)

**Date:** 2026-07-12  
**Android baseline:** owner-signed tickets **07** (launch/gallery) + **08** (editor/export)  
**Archive:** `docs/parity/v2.10.0/captures/` · policy `protocol/image-pick-policy.md`  
**Process:** align to **signed** Android product behavior, not branch WIP aesthetics. Platform edges stay narrow.

## Signed Android product truths (source of alignment)

| Area | Signed Android behavior |
|------|-------------------------|
| Launch | Dark launch; Choose Images; About; **logo uses `ColoredImageVIew` gradient animation** (same widget as v2.10.0) |
| Gallery primary | **In-app `GalleryDialog`** after permission |
| Gallery secondary | Gallery top-right → **system Photo Picker** |
| Editor | Shared shells; top logo navigate-up; filmstrip; Content/Style/Layout |
| Text watermark | Tap **Text** → **Edit watermark** sheet; edit field; **template icon top-end**; Confirm |
| Export | Sheet over dimmed editor; JPEG/PNG; continuous quality UI + **snap ×20 on release**; centered export-list preview; Export CTA |
| Renderer | **ADR-0018 / Option C2 (P3.5):** Android preview+export **default on** (debug **and** release) for commonMain `CommonWatermarkPipeline` / `WatermarkCellComposer` (same algorithm as Desktop/iOS); native `WatermarkRenderer` is **flag-off fallback** until Gate 4 cleanup. **Not** byte-parity with pre-C2 native goldens (CJK/engine delta expected). |

## Exception registry (one line each)

| ID | Surface / behavior | iOS | Desktop | Classification | Why (one line) |
|----|--------------------|-----|---------|----------------|----------------|
| E01 | Launch screen product route | Shared `LaunchScreenShell` via CMP host | **Target: ADD** shared Launch (U0 2026-07-12) | **Aligned intent (structure)** | Owner: iOS/Desktop chrome aligns to Android; Desktop no longer permanent editor-only. |
| E02 | In-app gallery grid | **Absent** — PhotosPicker | **Absent** — AWT FileDialog + drop | **Exception (iOS+Desktop) — permanent** | U0: system pick forever off-Android; do not invent gallery. |
| E03 | Gallery top-right system picker | N/A (no in-app gallery host) | N/A | **N/A** | Secondary only exists when primary is in-app gallery (Android). |
| E04 | About production route | **Target: ADD** shared About (U0) | **Target: ADD** shared About (U0) | **Aligned intent (structure)** | U0: add About route; URL open stays platform edge. Was DEBUG-only; production route is the new target. |
| E05 | Editor shared CMP shell | `IosEditorScreenHost` + `EditorScreenShell` | `DesktopWindow` + `EditorScreenShell` | **Aligned (route of record)** | Same shared shell; platform injects preview/actions. U1+ shared bottom controls. |
| E06 | Photo strip multi-image | **Target: YES when multi-image** | **Target: YES when multi-image** | **Aligned intent** | U0: filmstrip when session image list non-empty; selection via session. |
| E07 | Text edit UX | Sheet path kept for XCUITest (`inlineEditable`/row→sheet) | Shared `TextContentOption` sheet-capable row | **Aligned intent** | Product: Text → edit dialog; iOS keeps Confirm sheet contract. |
| E08 | Templates | SwiftUI strip + shared Room bridge | Shared `EditorTemplateSheetHost` + Room | **Aligned (capability)** | Both persist templates; UI chrome differs by platform edge. |
| E09 | Share out | System ShareLink / share sheet | OS share substitute (reveal folder) after sheet export | **Exception (mechanism only)** | **Panel** is shared `SaveExportSheetShell` (Android Compose); only share **mechanism** is platform. |
| E10 | Save / export destination | Photos library after sheet Export CTA | User dir / unique names after sheet Export CTA | **Exception (mechanism only)** | **Panel** is shared `SaveExportSheetShell`; only write destination is platform (no MediaStore). |
| E11 | Text/icon raster engine | Skiko `WatermarkCellComposer` via common pipeline | Same common pipeline | **Aligned intent (engine)** | **ADR-0018 C2 P3.5:** all three platforms share `CommonWatermarkPipeline` / `WatermarkCellComposer` by default (Android flags default **on** for debug+release). Native Android `WatermarkRenderer` is flag-off fallback only — **no** byte-parity claim vs historical native goldens. |
| E12 | CJK metrics | MultiParagraph / Skiko | MultiParagraph / Skiko | **Exception (metrics residual)** | Even under C2, CJK still differs from **historical** v2.10.0 StaticLayout baselines (dual-path IoU evidence). Cross-platform Skiko MultiParagraph is the **unified** product engine under C2; residual is font/host AA, not “Android forever native.” |
| E13 | EXIF on decode | Skia bakes orientation | ImageIO + manual EXIF bake | **Aligned (policy)** | Upright decode policy; different edge implementation. |
| E14 | PHPicker grid automation | Residual unproven | N/A | **Toolchain residual** | XCUITest cannot address PHPicker cells on beta toolchain; fixture seam is valid product proof path — **no endless re-proof**. |
| E15 | Packaging / distribution | Xcode `.app` + Shared.framework | Compose Desktop distributable (supported JDK) | **Exception (edge)** | Platform packaging only; not Android Play pipeline. |
| E16 | Permissions model | Photos limited access | None (user FS) | **Exception** | No Android storage permission UX. |

## What is **not** claimed

- iOS/Desktop **pixel 1:1** with Android v2.10.0 screenshots  
- CJK **byte** or tight FNV equality across engines  
- Real PHPicker cell selection automation on current beta Xcode  
- Full multi-screen Desktop/iOS product chrome matching Android nav graph  

## Verification (in-scope behaviors)

Evidence root: `build/s4d383-b3-align/` (commands + exits recorded with this ticket).

| Check | Scope | Command / artifact |
|-------|--------|-------------------|
| Shared Desktop tests | Geometry, composer, prefs, templates, desktop helpers | `:shared:desktopTest` → **132/0** (`build/s4d383-b3-align/01-shared.log`, EXIT 0) |
| iOS shared compile | Editor/shell/host klib still builds | `:shared:compileKotlinIosSimulatorArm64` → **EXIT 0** (same log) |
| Desktop product flow | Headless open→edit→save spine | `:desktopApp:run --args='--headless'` → **EXIT 0** (`02-desktop-headless.log`) |
| iOS product UI | Prior suite + hosts (not re-run this slice) | Cite `build/s4d383-a5a-final/` iosAppUITests **19/0** at A5a; text Confirm sheet contract retained |
| Android signed archive | Source of alignment | `docs/parity/v2.10.0/captures/` + tickets 07/08 owner-approved |

## Alignment posture by platform

### iOS

- **Keep:** system PhotosPicker (E02 permanent), Save/Share system UI, common Skiko raster (E11/E12 under C2).  
- **U0/C2:** single Compose product root via `ProductApp`; shared Launch/About/Editor; session-owned config; filmstrip when multi-image.  
- **Do not:** invent in-app GalleryDialogShell.  

### Desktop

- **Keep:** AWT open/save/drop (E02), headless witness, common Skiko raster (E11/E12 under C2).  
- **U0/C2:** shared Launch + About + Editor via `ProductApp`; config via session; filmstrip when multi-image.  
- **Do not:** invent in-app gallery grid.  

## Unblocks

Ticket **10** (DoD audit / PR #358 graduation proposal) requires 05–09; with 09 complete, **10 is the remaining program close gate** (still not auto-merge).
