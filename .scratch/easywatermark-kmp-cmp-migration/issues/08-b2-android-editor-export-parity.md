# 08 — B2 Android editor/save/export 1:1 parity sign-off (owner)

**What to build:** Production vs debug pairs for editor controls, watermark preview, save, and export under the Phase B protocol. Android text/icon/composition **remain native** (`WatermarkRenderer`) — do not “fix” via commonMain cell composer. Persist bytes unchanged. **Owner must explicitly approve** each signed screen/state.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **complete — owner approved** (2026-07-12)  
**Owner sign-off:** **yes** — owner replied *「ok，没问题」* after editor + export + centered export-list preview review.

## Acceptance checklist

- [x] Archive complete for editor/save/export matrix
- [x] Owner sign-off comments **or** punch-list (no self-sign) — **owner approved**
- [x] No S4d-8/17/190 draw-swap reopen; no silent golden rebaseline

## Delivered

| Area | Behavior | Evidence |
|------|----------|----------|
| Editor top bar | Toolbar logo + `Navigate up` | `editor-after-p0-fix.png` |
| Text watermark | Tap **Text** → **Edit watermark** sheet; template **top-end**; Confirm | `editor-text-sheet-open.png` |
| Text/Icon selection | Selected chip highlight | editor captures |
| Filmstrip | Larger thumbs (56dp) | editor captures |
| Export sheet | Dimmed editor peek; continuous quality + 20-step snap on release | `export-after-p1-fix.png` |
| Export list preview | Horizontally centered | `export-preview-centered.png` |
| HTML board | `captures/compare-en-dark.html` | |

## Commits (selected)

- `188c413f` editor chrome  
- `24097a48` Text → sheet + template top-end  
- `33284980` export peek + continuous quality  
- `72a19071` center export list preview  

## Residual (non-blocking)

- Logo monochrome vs prod yellow tint  
- M3 quality thumb shape vs prod round thumb  

## Guardrails

Android production raster/composition stays native. No commonMain cell draw-swap. No silent golden rebaseline. JPEG quality snap-to-20 on release retained (ADR-0014).

## Next

Ticket **07** (launch/gallery) remains punch-list open if owner wants prod Choose-Images residual handled; otherwise Phase B can proceed to **09** only after **07** + **08** both closed — **08 is closed**.
