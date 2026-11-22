# ADR-0041: Reusable sticker asset library

Status: Proposed (design direction approved by owner on 2026-11-14; implementation pending verification and device sign-off).

## Context

The interim Sticker Library stored 10 fields per saved sticker (`id`, `icon`, `textSize`, `alpha`, `degree`, `tileMode`, `hGap`, `vGap`, `offsetX`, `offsetY`) inside `stickers.catalog`, while applying a saved user sticker overwrote 6 live config fields plus `CLAMP` offset — whereas applying a built-in Phosphor sticker kept live styling, reset `CLAMP` offset to center, and wrote a new UUID PNG on every click. This created a mismatch between built-in and user stickers, prevented `BuiltInSticker` cells from showing active selection, left the `StickerPanelSheet` open after importing a new icon, and left `Repeat` mode without an obvious one-tap clear action alongside `Single` mode's on-canvas `×` handle.

Meanwhile, saving multi-field layout/style snapshots belongs to a dedicated global Presets system rather than per-image sticker assets.

## Decision

- **Asset-only `Sticker` model:** `Sticker` stores `(id: String, icon: MediaRef)` only. `StickerCatalog` writes 2 fields per line (`id` and `icon.value`) and continues decoding legacy 10-field catalog lines by taking `parts[0..1]` while rejecting malformed lines.
- **Swap icon only, preserve live layout and placement:** Selecting either a built-in Phosphor mark or a saved user sticker dispatches only `WatermarkConfigChange.Icon(ref)`. Live `WaterMark` style/layout (`textSize`, `alpha`, `degree`, `tileMode`, `hGap`, `vGap`) and valid `Single` (`CLAMP`) fractional offsets in `0f..1f` remain untouched; `StickerPlacement.sanitizeLiveSingleOffset` coerces only non-finite or out-of-bounds `Single` offsets. Remove unused `saveCurrentSticker()` / `onSaveSticker` host wiring.
- **Deterministic built-in icon files:** The 6 `BuiltInSticker` entries use stable keys (`copyright`, `prohibit`, `shield`, `seal`, `camera`, `lock`) written atomically to deterministic app-owned filenames (`icon-builtin-<key>.png` on Android/Desktop; `icon_builtin_<key>.png` on iOS). Repeated built-in picks overwrite in place without leaking UUID PNGs, and `BuiltInSticker.matches` drives cell selection highlight across platforms.
- **Direct inline presentation & unified architectural geometry:** All three hosts (Android, Desktop, iOS) route `useLargeDialog` through `usesLargeScreenDialog(layoutClass)` (`>= 800 dp` per ADR-0026) and align sticker UI to `EwmTheme.shapes.chipRadiusDp` (`2.dp` corners), `editorAccentColor()`, `editorSelectedContainerColor()`, and `editorChromeColor()`:
  - **Compact/Medium (`< 800 dp`):** `StickerPanelOnIcon` renders a direct horizontal `InlineStickerRail` (`testTag("stickerInlineRail")`) inside the fixed `96.dp` bottom control slot for 1-tap unobstructed live preview: a left-pinned grid expand button (`stickerExpand` opening `StickerPanelSheet`), vertical hairline, `+` import tile (`48.dp`), imported user stickers first, the 6 built-in Phosphor marks, and a pinned trailing clear action (`stickerClear`) that fades without shifting tiles horizontally. Tapping `stickerExpand` opens the `2.dp` darkroom `StickerPanelSheet` (`dismissOnSelect = false` for continuous live comparison, `Mine` / `Built-in` grouping when user stickers exist, and an explicit `Manage` toggle exposing one-tap delete badges alongside `Remove` / close controls).
  - **Expanded/Wide (`>= 800 dp`):** `StickerPanelOnIcon` embeds the 4-column `2.dp`-radius architectural sticker grid (`stickerInlinePanel`, `8.dp` spacing) directly in `EditorInspectorPanel` with `Mine` / `Built-in` section grouping when user stickers exist, an explicit `Manage` toggle (`stickerManageToggle`) exposing corner delete badges, hover `×` for desktop pointers, and long-press delete for user stickers.
  - **Rotated Single-mode selection box:** `RasteredWatermarkCell` and `OverlayCell` propagate unrotated content dimensions (`contentWidth × contentHeight`), and `InteractiveOverlayPreview` rotates the crisp `1.dp` hairline selection box + free-corner architectural L-brackets in 1:1 lockstep with the watermark (`computeRotatedSelectionFrame`) alongside unified `24.dp` `2.dp`-radius corner handles and a monospace degree HUD.

## Acceptance

- Common unit/UI tests verify `StickerCatalog` round-trip and legacy 10-field decode, `StickerPlacement.sanitizeLiveSingleOffset`, `RasteredWatermarkCell` unrotated content bounds across angles, `computeRotatedSelectionFrame`, rotated handle coordinate tracking, inline rail & inspector selection/clear/long-press delete (`:shared:desktopTest`, `:shared:iosSimulatorArm64Test`).
- Android unit and debug APK builds pass (`:app:testDebugUnitTest`, `:app:assembleDebug`), and `:desktopApp:classes` compiles cleanly.
- Physical-device / visual review via `e2e-testmap` Mode B remains required before merge.
