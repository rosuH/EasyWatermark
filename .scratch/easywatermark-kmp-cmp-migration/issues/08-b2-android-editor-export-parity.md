# 08 — B2 Android editor/save/export 1:1 parity sign-off (owner)

**What to build:** Production vs debug pairs for editor controls, watermark preview, save, and export under the Phase B protocol. Android text/icon/composition **remain native** (`WatermarkRenderer`) — do not “fix” via commonMain cell composer. Persist bytes unchanged. **Owner must explicitly approve** each signed screen/state.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **editor P0 code fix landed — re-capture done; export still open** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).  
**Commit:** `188c413f` — *Fix Android editor chrome for Phase B parity*

## Acceptance checklist

- [x] Archive complete for editor/save/export matrix (en/dark open sheet + text-mode editor minimum)
- [ ] Owner sign-off comments **or** punch-list (no self-sign) — **editor pending owner; export open**
- [x] No S4d-8/17/190 draw-swap reopen; no silent golden rebaseline

## Archive

| State | Prod | Debug |
|-------|------|-------|
| editor (prefer after fix) | `production/…/editor-text-mode.png` | **`debug/…/editor-after-p0-fix.png`** |
| editor (before fix) | | `debug/…/editor-text-mode-clean.png` |
| export-sheet-open | `production/…/export-sheet-open.png` | `debug/…/export-sheet-open-clean.png` |
| HTML board | `captures/compare-en-dark.html` | |

## Editor P0 fix (landed)

| Issue | Fix |
|-------|-----|
| Top leading back arrow | Android `EditorTopBar` → `ic_logo_tool_bar` + a11y `Navigate up` (still `onBack`) |
| Content text clipped | `height(56)` → `heightIn(min=56)`; control frame vertical padding 8 |
| Content not field-like | `TextContentOption` default **live TextField** (`inlineEditable=true`); **iOS keeps sheet** (`false`) for XCUITest Confirm |
| Text/Icon weak selection | Selected option chip `surfaceVariant` |
| Filmstrip small | Photo strip item 40→56 dp |

**Grok after fix:** logo present; `👋 DO NOT REDISTRIBUTE` TextField + underline visible; Text chip selected; amber tile OK. Residual: logo monochrome vs prod yellow tint.

## Punch-list remaining

1. **Owner:** `OWNER SIGN-OFF 08 editor: approved` or request logo color polish.  
2. **Export P1 (next code):** quality slider visual + sheet overlay.  
3. Optional Style/Layout/icon mode pairs.

## Guardrails

Android production raster/composition stays native. No commonMain cell draw-swap. No silent golden rebaseline.
