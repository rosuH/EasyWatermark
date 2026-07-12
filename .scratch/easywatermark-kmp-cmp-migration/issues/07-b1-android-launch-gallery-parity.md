# 07 — B1 Android launch/gallery 1:1 parity sign-off (owner)

**What to build:** Side-by-side production v2.10.0 vs debug captures for launch and gallery (empty/loaded, pick/share-in entry). Grok views screenshots/recordings; archive under the 06 scaffold. **Owner must explicitly approve** each signed screen — agents never self-sign.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **complete — owner approved** (2026-07-12)  
**Owner sign-off:** **yes** — owner: *「7 都 approved」* (launch + gallery).

## Acceptance checklist

- [x] Archived production/debug pairs for launch/gallery matrix (en/dark minimum set)
- [x] Owner comment approving sign-off **or** punch-list of remaining deltas (no silent pass) — **owner approved both**
- [x] Out of scope: editor/export pixels; Desktop/iOS; renderer policy changes

## Archive

- Pass 1: `docs/parity/v2.10.0/captures/COMPARISON-2026-07-12-en-dark.md`  
- Pass 2: `docs/parity/v2.10.0/captures/CONTINUATION-2026-07-12-pass2.md`  
- HTML: `docs/parity/v2.10.0/captures/compare-en-dark.html`  
- Pick policy: `docs/parity/v2.10.0/protocol/image-pick-policy.md`

| State | Prod | Debug |
|-------|------|-------|
| launch-idle | `production/…/launch-idle.png` | `debug/…/launch-idle-clean.png` |
| gallery primary (product policy) | *API36 residual: Choose Images → system picker* | `gallery-inapp-primary.png` |
| gallery secondary | — | `gallery-topright-system-picker.png` |

## Owner product policy (binding)

| Platform | Primary | Secondary |
|----------|---------|-----------|
| Android | In-app gallery | Top-right → system Photo Picker |
| iOS / Desktop | System pick / file dialog | — |

**Launch logo animation:** same `ColoredImageVIew` as production; owner confirmed OK.

## Residuals (accepted with sign-off)

- Production Choose Images may open system Photo Picker on this emulator/API while **product target** remains gallery-first (debug). Documented; not a reason to flip debug to system-first.
- Minor launch logo tint differences under dynamic color.

## Next

**07 + 08 both complete** → ticket **09** (iOS/Desktop alignment + exception registry) is **unblocked**.
