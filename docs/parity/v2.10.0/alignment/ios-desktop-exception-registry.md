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
| Renderer | **Native** `WatermarkRenderer` text/icon/compose (ADR-0004 / S4d-8/17/190) |

## Exception registry (one line each)

| ID | Surface / behavior | iOS | Desktop | Classification | Why (one line) |
|----|--------------------|-----|---------|----------------|----------------|
| E01 | Launch screen product route | Shared `LaunchScreenShell` via CMP host | **Absent** — window opens editor | **Exception (Desktop)** | Desktop is editor-window-only (ticket 04); no multi-screen nav product. |
| E02 | In-app gallery grid | **Absent** — PhotosPicker | **Absent** — AWT FileDialog + drop | **Exception (iOS+Desktop)** | Owner pick policy: system pick only off-Android (02/04 + image-pick-policy). |
| E03 | Gallery top-right system picker | N/A (no in-app gallery host) | N/A | **N/A** | Secondary only exists when primary is in-app gallery (Android). |
| E04 | About production route | **Absent** (DEBUG witness only) | **Absent** | **Exception (iOS+Desktop)** | Owner-signed absence (03/04); not invented for matrix fill. |
| E05 | Editor shared CMP shell | `IosEditorScreenHost` + `EditorScreenShell` | `DesktopWindow` + `EditorScreenShell` | **Aligned (route of record)** | Same shared shell; platform injects preview/actions. |
| E06 | Photo strip multi-image | `showPhotoStrip=false` | No Android-style strip | **Exception** | iOS single-source bring-up; Desktop last-file source model. |
| E07 | Text edit UX | Sheet path kept for XCUITest (`inlineEditable`/row→sheet) | Shared `TextContentOption` sheet-capable row | **Aligned intent** | Product: Text → edit dialog; iOS keeps Confirm sheet contract. |
| E08 | Templates | SwiftUI strip + shared Room bridge | Shared `EditorTemplateSheetHost` + Room | **Aligned (capability)** | Both persist templates; UI chrome differs by platform edge. |
| E09 | Share out | System ShareLink / share sheet | OS share substitute on last real save | **Exception (mechanism)** | No Android Intent; OS-native share substitutes only. |
| E10 | Save / export destination | Photos library + share | User dir / FileDialog / unique names | **Exception (mechanism)** | No MediaStore; FS/Photos edges. |
| E11 | Text/icon raster engine | Skiko `WatermarkCellComposer` | Skiko `WatermarkCellComposer` | **Exception (engine)** | Android stays native StaticLayout/bitmap path; no byte-parity claim. |
| E12 | CJK metrics | MultiParagraph / Skiko | MultiParagraph / Skiko | **Exception (metrics)** | **No** StaticLayout vs MultiParagraph byte/CJK pixel parity claims (ADR-0004 / S4d-17). |
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

- **Keep:** shared editor host, system PhotosPicker, Templates Swift edge, Save/Share system UI, DEBUG witnesses only behind launch args.  
- **Do not:** invent in-app GalleryDialogShell or About production route without new owner ticket.  
- **Renderer:** Skiko path; accept E11/E12.  

### Desktop

- **Keep:** single editor window, AWT open/save/drop, shared options + templates, headless witness.  
- **Do not:** invent Launch/Gallery/About screens for symmetry (ticket 04).  
- **Renderer:** Skiko path; accept E11/E12.  

## Unblocks

Ticket **10** (DoD audit / PR #358 graduation proposal) requires 05–09; with 09 complete, **10 is the remaining program close gate** (still not auto-merge).
