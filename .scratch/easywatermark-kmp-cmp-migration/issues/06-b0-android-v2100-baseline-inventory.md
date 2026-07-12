# 06 — B0 Android v2.10.0 baseline inventory and parity archive scaffold

**What to build:** Durable inventory of production **v2.10.0** screens × states × locales × themes and a capture-archive scaffold (e.g. under `docs/parity/` or successor) with protocol for production app `me.rosuh.easywatermark` vs debug `me.rosuh.easywatermark.debug`. Control matrix: en/zh, font scale, light/dark, representative images; media permission noted. **No owner sign-off claims** in this ticket.

**Blocked by:** 05 A5e Phase A closeout (must **PASS** first) — **satisfied**.

**Status:** **complete** (2026-07-12)  
**Does not complete:** tickets 07/08 owner sign-off, Phase B parity, §9 DoD.

## Acceptance checklist

- [x] Screen/state inventory complete for launch, gallery, editor, save/export (and A5 edge list)
- [x] Archive layout + capture protocol documented
- [x] Explicit “ready for 07 and 08 sign-off work” note
- [x] No inventing Phase B code polish unless capture is blocked (then escalate)

## Deliverables

| Path | Role |
|------|------|
| `docs/parity/v2.10.0/README.md` | Archive root; package IDs; ticket mapping |
| `docs/parity/v2.10.0/protocol/capture.md` | Device pair protocol, naming, permissions, controls |
| `docs/parity/v2.10.0/inventory/screens.md` | Screen × state matrix + A5 edge cross-ref + 07/08 priority |
| `docs/parity/v2.10.0/captures/{production,debug}/{en,zh}/{light,dark}/` | Empty capture slots (`.gitkeep`) |

## Device check (2026-07-12)

- Emulator `emulator-5554` available.
- Production installed: `me.rosuh.easywatermark` **versionName=2.10.0** versionCode=21000.
- Debug installed: `me.rosuh.easywatermark.debug` (side-by-side).

## Ready for 07 and 08

**Yes.** Tickets **07** (launch/gallery) and **08** (editor/export) may begin capture + owner sign-off using this inventory and protocol. No screen is claimed signed-off by this ticket.

## Guardrails

- No Phase B UI polish code in this ticket.
- No owner “looks good” claims.
- User research file remains uncommitted if still present.
