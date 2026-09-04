# Image pick policy (owner-signed, 2026-07-12)

**Owner decision (commander prompt):**

| Platform | Primary pick path | Secondary / edge |
|----------|-------------------|------------------|
| **Android** | **In-app gallery** first (`GalleryDialog` / `GalleryDialogShell`) after “Choose Images” (with media permission) | **Top-right gallery action** (search / image-search icon) launches **system Photo Picker** (`PickMultipleVisualMedia` / Photo Picker) |
| **iOS** | **System PhotosPicker / PHPicker** by default | No production in-app `GalleryDialogShell` (ticket 02 exception remains) |
| **Desktop** | **System / AWT file dialog + drag-drop** by default | No in-app gallery product screen (ticket 04) |

## Implications for Phase B

1. **Android parity target** for the launch → pick flow is:  
   Launch → permission if needed → **in-app gallery** (not system Photo Picker as the primary surface).  
   System Photo Picker is a **secondary** entry (gallery top-right), and also editor “add more images” where wired.
2. Debug `ComposeMainActivity` already matches this shape (`GalleryDialog` + `onPickImageViaSystem` on search).  
3. If production v2.10.0 on a given API opens system picker for “Choose Images”, treat as **platform/API residual to document** or a **prod defect vs product intent** — do not flip Android debug to system-picker-primary without owner re-decision.
4. iOS/Desktop exceptions (02/04) stay: system picker / file dialog only.

## Capture notes

- Prefer screenshots of **in-app gallery** for Android B1 primary pairs.  
- Also capture **gallery top-right → system Photo Picker** as a secondary pair when signing gallery.
