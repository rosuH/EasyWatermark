# 08 — B2 Android editor/save/export 1:1 parity sign-off (owner)

**What to build:** Production vs debug pairs for editor controls, watermark preview, save, and export under the Phase B protocol. Android text/icon/composition **remain native** (`WatermarkRenderer`) — do not “fix” via commonMain cell composer. Persist bytes unchanged. **Owner must explicitly approve** each signed screen/state.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **captures archived — punch-list open (awaiting owner)** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).

## Acceptance checklist

- [x] Archive complete for editor/save/export matrix (en/dark open sheet + text-mode editor minimum)
- [ ] Owner sign-off comments **or** punch-list (no self-sign) — **punch-list ready**
- [x] No S4d-8/17/190 draw-swap reopen; no silent golden rebaseline

## Archive

Full write-up: `docs/parity/v2.10.0/captures/COMPARISON-2026-07-12-en-dark.md`

| State | Prod | Debug |
|-------|------|-------|
| editor-text-mode | `captures/production/en/dark/editor-text-mode.png` | `captures/debug/en/dark/editor-text-mode.png` |
| export-sheet-open | `captures/production/en/dark/export-sheet-open.png` | `captures/debug/en/dark/export-sheet-open.png` |

## Grok findings (summary)

- **Editor P0:** top-leading logo vs back arrow; preview density/color/text (debug prefs polluted with `S4d-254 smoke` green); Content tab text surface incomplete vs prod inline field.  
- **Export P1:** same controls (JPEG/80/list/export CTA); quality **slider thumb position wrong vs value 80** on debug; sheet less overlaid on preview.

## Punch-list for owner

1. Reset device watermark prefs for fair re-capture (or clear debug app data) before next pass.  
2. Prioritize editor top bar + content chrome vs preview polish.  
3. Fix or accept quality slider visual.  
4. After fixes, re-capture export progress/done states.

**Reply examples:**  
- `OWNER SIGN-OFF 08 editor: …` / `OWNER SIGN-OFF 08 export: …`  
- Or prioritized fix list.

## Guardrails

Android production raster/composition stays native. No commonMain cell draw-swap. No silent golden rebaseline.
