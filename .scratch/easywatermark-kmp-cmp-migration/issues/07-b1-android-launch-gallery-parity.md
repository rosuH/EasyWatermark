# 07 — B1 Android launch/gallery 1:1 parity sign-off (owner)

**What to build:** Side-by-side production v2.10.0 vs debug captures for launch and gallery (empty/loaded, pick/share-in entry). Grok views screenshots/recordings; archive under the 06 scaffold. **Owner must explicitly approve** each signed screen — agents never self-sign.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **captures archived (pass 2) — punch-list open (awaiting owner)** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).

## Acceptance checklist

- [x] Archived production/debug pairs for launch/gallery matrix (en/dark minimum set)
- [ ] Owner comment approving sign-off **or** punch-list of remaining deltas (no silent pass) — **punch-list ready**
- [x] Out of scope: editor/export pixels; Desktop/iOS; renderer policy changes

## Archive

- Pass 1: `docs/parity/v2.10.0/captures/COMPARISON-2026-07-12-en-dark.md`  
- Pass 2: `docs/parity/v2.10.0/captures/CONTINUATION-2026-07-12-pass2.md`

| State | Prod | Debug |
|-------|------|-------|
| launch-idle | `…/production/…/launch-idle.png` | `…/debug/…/launch-idle-clean.png` (after `pm clear`) |
| gallery primary (policy) | *residual: Choose Images → system picker* | `gallery-inapp-primary.png` ✅ |
| gallery secondary (policy) | *no in-app host on this prod path* | `gallery-topright-system-picker.png` ✅ system picker |

## Grok findings (summary)

- **Launch:** structure matches; logo tint slightly different.  
- **Gallery:** session capture showed prod → system Photo Picker, debug → in-app gallery.

## Owner product policy (2026-07-12) — **recorded**

- **Android:** **in-app gallery first**; **top-right** entry launches **system Photo Picker**.  
- **iOS / Desktop:** default **system** photo picker / file dialog (unchanged exceptions).  
- Full text: `docs/parity/v2.10.0/protocol/image-pick-policy.md`.

## Punch-list (updated)

1. Accept launch as-is or require logo tint fix?  
2. ~~Gallery primary path~~ → **decided: in-app gallery primary**. Re-capture production under permissioned path; if prod still jumps to Photo Picker as primary, file as **prod residual / fix target**, not “make debug system-first”.  
3. Capture **secondary** pair: gallery top-right → system Photo Picker (prod + debug).  
4. Owner sign-off after re-capture.

**Reply examples:**  
- `OWNER SIGN-OFF 07 launch: approved`  
- `OWNER SIGN-OFF 07 gallery primary: approved`
