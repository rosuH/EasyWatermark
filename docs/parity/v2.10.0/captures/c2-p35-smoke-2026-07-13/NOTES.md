# C2 P3.5 smoke — 2026-07-13

## Code change (P3.5)

`CommonRasterFlags.useCommonRasterPreview` / `useCommonRasterExport` default **`true`** for **debug and release** (was `BuildConfig.DEBUG` only).

Native `WatermarkRenderer` remains flag-off fallback (Gate 4 delete still owner-gated).

## Export panel contract

| Layer | Implementation |
|-------|----------------|
| Panel + interaction | Shared `SaveExportSheetShell` (Android Compose) |
| Write / share | Platform edges only |

## Artifacts

| Path | What |
|------|------|
| `android/01-launch.png` … `04-export-sheet.png` | Device emulator-5554 flow → export sheet open |
| `android/baseline-prod-export-sheet.png` | v2.10.0 production archive reference |
| `android/baseline-debug-export-sheet.png` | Prior debug clean sheet reference |
| `ios/03-fixture-editor.png` | Fixture-seeded editor (shared CMP) |
| `desktop/headless-export.jpg` | `:desktopApp:run --args='--headless'` export spine |

## Gates run

| Gate | Result |
|------|--------|
| C2 dual-path + export port unit tests + `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:desktopApp:run --args='--headless'` | EXIT 0 |
| iOS `xcodebuild` Debug simulator | BUILD SUCCEEDED |
| `testFixtureRenderPreviewAndExport` | Updated for SaveExportSheetShell (re-run after this NOTES) |

## Android export sheet layout (smoke)

Observed on debug: **Output format / JPEG / Quality 80 / Export list / Export to the album** — matches signed Android export chrome structure. Preview under dimmed editor uses common raster (P3.5 default on).

## Residuals

- Full XCUITest suite re-run after SaveExportSheetShell test update.
- Desktop interactive export-sheet screenshot is manual GUI residual (headless proves raster write path only).
- Gate 4: delete native Android builders after owner soak confidence.

## iOS XCUITest (re-run after SaveExportSheetShell)

`testFixtureRenderPreviewAndExport` → **TEST SUCCEEDED** (36s) · xcresult: `ios/fixture-export.xcresult`

Flow proven: fixture → editor preview → Save top bar → **SaveExportSheetShell** → Export → Share sheet.
