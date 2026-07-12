# 02 — A5b iOS gallery production route or owner-signed picker-only edge

**What to build:** Either a real production path for shared `GalleryDialogShell` on iOS (only if it is a genuine product multi-select gallery), **or** an evidence pack plus **owner-signed** PhotosPicker-only exception. Do not confuse PHPicker XCUITest grid-cell automation residual (toolchain) with product behavior.

**Blocked by:** None — can start immediately (parallel with 01, 03, 04).

**Status:** **complete** (S4d-383 / A5b accepted 2026-07-12)  
**Disposition:** **PhotosPicker-only Phase A exception** (do not ship production `GalleryDialogShell` on iOS).

## Acceptance checklist

- [x] Production gallery shell on iOS **or** owner-signed picker-only exception recorded on this ticket
- [x] PHPicker automation residual documented as toolchain, not “gallery missing”
- [x] No invented gallery product that product does not need
- [x] No new deps; persistence bytes sacred; not Phase B pixel work

## Owner sign-off (recorded)

**Signed:** 2026-07-12 — owner selected *Sign both 02 + 03* via commander prompt.  
**Text:** PhotosPicker-only Phase A exception for iOS gallery — **approved**.

### Cross-platform pick policy (same day, Phase B)

Owner clarified **platform matrix** (also in `docs/parity/v2.10.0/protocol/image-pick-policy.md`):

| Platform | Primary | Secondary |
|----------|---------|-----------|
| Android | In-app gallery | Top-right → system Photo Picker |
| **iOS** | **System PhotosPicker** (this ticket) | — |
| Desktop | System file dialog / drop | — |

iOS Phase A exception **unchanged** (no production `GalleryDialogShell`).

---

## Evidence pack (commander review)

### What Android product has

- `ComposeMainActivity` navigates `GalleryDialogRoute` → `GalleryDialog` → shared `GalleryDialogShell`.
- In-app multi-select of device images with selected-count UI, plus a path to system picker (`onPickImageViaSystem`).
- This is a real Android product surface (not DEBUG-only).

### What iOS production has today (after S4d-383 / ticket 01)

| Surface | Production? | Notes |
|---------|-------------|--------|
| `PhotosPicker` (source photo) | **Yes** | `ContentView` launch + editor “pick another photo” |
| `PhotosPicker` (icon watermark) | **Yes** | Separate icon picker binding |
| Shared `GalleryDialogShell` | **No** | DEBUG witness only (`galleryDialogShellWitness` / `-sharedComposeWitness gallery`) |
| Multi-select in-app grid | **No** | Not a product path on iOS |

Primary production entry: `SharedComposeLaunchScreen` → `IosLaunchScreenHost` → `LaunchScreenShell` with `onPickImage` → SwiftUI `photosPicker` / `PhotosPickerItem` → `WatermarkWorkflow`.

### PHPicker XCUITest residual (toolchain, not product)

- S4d-57/S4d-58: XCUITest can open out-of-process `PHPickerViewController` but **cannot address grid cells** on Xcode-27-beta / iOS-27 (`collectionViews` empty; limited-access banner noise).
- Fixture seam `-uiTestFixtureImage` proves render/export without picking a real cell.
- **This residual is automation tooling, not “gallery product missing.”** Shipping `GalleryDialogShell` would not fix PHPicker cell automation.

### Why inventing GalleryDialogShell on iOS would be wrong for Phase A

1. iOS already has a first-class system multi-select photo UI (`PhotosPicker` / PHPicker); Android’s in-app gallery exists because MediaStore + permission UX differ.
2. Building a fake in-app gallery over CMP only for matrix symmetry invents product surface, storage/query glue, and a11y contracts without a user need.
3. Ticket forbids invented gallery product and Phase B pixel work.

### Proposed owner-signed exception (Phase A)

| Item | Decision |
|------|----------|
| iOS production image pick | **PhotosPicker / PHPicker only** (Swift edge) |
| Shared `GalleryDialogShell` on iOS | **Absent in production**; DEBUG witness + link proof only |
| Multi-image | Optional future: PhotosPicker multi-selection edge if product needs batch — **not** Android gallery clone |
| XCUITest PHPicker cells | Documented **toolchain residual**; fixture seam remains valid proof path |
| Duration | Phase A; revisit only if owner requires Android-parity in-app gallery on iOS (then a new ticket) |

### Guardrails confirmed

- No new dependencies.
- Persistence bytes untouched.
- Android gallery/native renderer policy untouched.
- Not Phase B pixel work.

---

## Owner sign-off

**Reply to accept:**  
`OWNER SIGN-OFF 02: PhotosPicker-only Phase A exception for iOS gallery — approved`

**Reply to reject / re-scope:**  
State whether production `GalleryDialogShell` (or multi-select PhotosPicker product work) is required instead.

Until signed, ticket **05 (A5e)** cannot claim A5 PASS for the gallery matrix cell.
