# 08 — B2 Android editor/save/export 1:1 parity sign-off (owner)

**What to build:** Production vs debug pairs for editor controls, watermark preview, save, and export under the Phase B protocol. Android text/icon/composition **remain native** (`WatermarkRenderer`) — do not “fix” via commonMain cell composer. Persist bytes unchanged. **Owner must explicitly approve** each signed screen/state.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **editor + export P0/P1 code fixes landed — re-capture done; awaiting owner sign-off** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).

## Acceptance checklist

- [x] Archive complete for editor/save/export matrix
- [ ] Owner sign-off comments **or** punch-list (no self-sign) — **awaiting owner**
- [x] No S4d-8/17/190 draw-swap reopen; no silent golden rebaseline

## Commits (this ticket)

| Change | Evidence |
|--------|----------|
| Editor logo / text sheet / selection | `188c413f`, `24097a48` · `editor-after-p0-fix.png`, `editor-text-sheet-open.png` |
| Export sheet + quality slider | this commit · `export-after-p1-fix.png` |

## Editor (summary)

- Logo leading (`Navigate up`), Text **button → Edit watermark sheet**, template **top-end** of sheet, Text chip selected, filmstrip 56dp.
- Residual: logo monochrome vs prod yellow.

## Export P1 fix (landed)

| Issue | Fix |
|-------|-----|
| Full-screen sheet | Drop `fillMaxSize`; wrap-height content so dimmed editor peeks |
| Discrete quality ticks | `steps=0` continuous track; **snap ×20 on release** (ADR-0014) |
| CTA clipped | Tighter vertical padding; preview box 145→110dp; export button visible |
| Layout density | Reduced title/export-list/top paddings |

**Grok after fix:** sheet over dimmed watermark preview; continuous quality track at 80; Export list thumb; **Export to the album** CTA on-screen. Residual: M3 thumb shape (vertical cap) vs prod round thumb.

## Punch-list for owner

1. `OWNER SIGN-OFF 08 editor: approved` (or request logo color polish)  
2. `OWNER SIGN-OFF 08 export: approved` (or request thumb style polish)  
3. Optional Style/Layout/icon mode pairs  

HTML: `docs/parity/v2.10.0/captures/compare-en-dark.html`

## Guardrails

Android production raster/composition stays native. No commonMain cell draw-swap. No silent golden rebaseline. JPEG quality snap-to-20 retained on release (ADR-0014).
