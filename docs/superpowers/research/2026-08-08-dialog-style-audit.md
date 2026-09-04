# Product dialog / sheet style audit — 2026-08-08

## Design contract (editor product)

Aligned with Figma olive editor + existing export sheet:

| Token | Value |
|-------|--------|
| Shape | `RectangleShape` (no M3 rounded dialog card) |
| Surface | `DesignEditorBg` (`#262611`) |
| Elevation | `tonalElevation = 0.dp` |
| Title | `titleMedium` / `onSurface` |
| Body | `bodyMedium` / `onSurfaceVariant` |
| Confirm | `TextButton` + `primary` (brand yellow) |
| Cancel | `TextButton` + `onSurfaceVariant` |

Shared helper: `shared/.../ui/compose/EwmConfirmDialog.kt`.

## Inventory

| Surface | Type | Before | After |
|---------|------|--------|--------|
| Template **use** confirm | AlertDialog | M3 default rounded / elevated | **EwmConfirmDialog** |
| Template **delete** confirm | AlertDialog | M3 default | **EwmConfirmDialog** |
| Template list sheet | ModalBottomSheet | Rectangle + surface | Rectangle + **DesignEditorBg** + 0 elev |
| Template edit sheet | ModalBottomSheet | Rectangle + surface | Rectangle + **DesignEditorBg** + 0 elev |
| Text content edit sheet | ModalBottomSheet | Rectangle + surface | Rectangle + **DesignEditorBg** + 0 elev |
| Custom color picker | ModalBottomSheet | Rectangle + surface + 0 elev | **DesignEditorBg** + 0 elev |
| Save / export sheet | ModalBottomSheet | Already DesignEditorBg + Rectangle | unchanged |
| Android editor **exit** confirm | AlertDialog | M3 default | **EwmConfirmDialog** + no outside/back dismiss |
| Android **Gallery** full-screen | `Dialog` | Full-bleed host (not confirm chrome) | intentional exception |
| iOS export / color / text | same shared sheets | via Template/Text/Color/Export | covered |

## Not product confirm dialogs

- `GalleryDialog` / `Dialog(usePlatformDefaultWidth=false)` — full-screen picker host; keep.
- System share / Photos picker — platform UI; out of scope.
- Recovery / crash UI — separate product surface if present later.

## Residual

- Non-EN `\?` locale strings still Weblate-owned (prior six-bugs residual).
- If more confirms appear, always use `EwmConfirmDialog` (lint residual: no raw `AlertDialog` in product paths).
