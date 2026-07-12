# 08 — B2 Android editor/save/export 1:1 parity sign-off (owner)

**What to build:** Production vs debug pairs for editor controls, watermark preview, save, and export under the Phase B protocol. Android text/icon/composition **remain native** (`WatermarkRenderer`) — do not “fix” via commonMain cell composer. Persist bytes unchanged. **Owner must explicitly approve** each signed screen/state.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **captures archived (pass 2 clean prefs) — punch-list open (awaiting owner)** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).

## Acceptance checklist

- [x] Archive complete for editor/save/export matrix (en/dark open sheet + text-mode editor minimum)
- [ ] Owner sign-off comments **or** punch-list (no self-sign) — **punch-list ready**
- [x] No S4d-8/17/190 draw-swap reopen; no silent golden rebaseline

## Archive

- Pass 1: `COMPARISON-2026-07-12-en-dark.md`  
- Pass 2: `CONTINUATION-2026-07-12-pass2.md` (prefer **clean** shots for editor/export)

| State | Prod | Debug (clean) |
|-------|------|----------------|
| editor-text-mode | `…/production/…/editor-text-mode.png` | `…/debug/…/editor-text-mode-clean.png` |
| export-sheet-open | `…/production/…/export-sheet-open.png` | `…/debug/…/export-sheet-open-clean.png` |

## Grok findings (summary)

- **Prefs pollution fixed** after `pm clear`: default text/color/tile matches prod amber dense tile.  
- **Editor P0 remaining:** top-leading logo vs back arrow; Content tab **missing inline text field**.  
- **Export P1 remaining:** quality **slider visual** (discrete + odd knobs) vs prod continuous; sheet chrome (full-screen vs over dimmed editor).

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
