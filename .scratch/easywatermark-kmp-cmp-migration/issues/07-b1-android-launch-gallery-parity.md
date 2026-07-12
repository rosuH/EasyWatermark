# 07 — B1 Android launch/gallery 1:1 parity sign-off (owner)

**What to build:** Side-by-side production v2.10.0 vs debug captures for launch and gallery (empty/loaded, pick/share-in entry). Grok views screenshots/recordings; archive under the 06 scaffold. **Owner must explicitly approve** each signed screen — agents never self-sign.

**Blocked by:** 06 B0 Android v2.10.0 baseline inventory/archive.

**Status:** **captures archived — punch-list open (awaiting owner)** (2026-07-12)  
**Owner sign-off:** **none** (agent does not self-sign).

## Acceptance checklist

- [x] Archived production/debug pairs for launch/gallery matrix (en/dark minimum set)
- [ ] Owner comment approving sign-off **or** punch-list of remaining deltas (no silent pass) — **punch-list ready**
- [x] Out of scope: editor/export pixels; Desktop/iOS; renderer policy changes

## Archive

See `docs/parity/v2.10.0/captures/COMPARISON-2026-07-12-en-dark.md` and:

| State | Prod | Debug |
|-------|------|-------|
| launch-idle | `captures/production/en/dark/launch-idle.png` | `captures/debug/en/dark/launch-idle.png` |
| gallery/picker | `…/gallery-or-picker.png` (system Photo Picker) | `…/gallery-or-picker.png` (in-app Choose picture) |
| selected | `…/picker-selected.png` | `…/gallery-selected.png` |

## Grok findings (summary)

- **Launch:** structure matches; logo tint slightly different.  
- **Gallery:** **P0 path mismatch** — production opens **system Photo Picker**; debug opens **in-app GalleryDialog**.

## Punch-list for owner

1. Accept launch as-is or require logo tint fix?  
2. **Gallery target:** system Photo Picker (match current prod on API 36 emulator) vs in-app gallery (match debug / older product)?  
3. After decision, re-capture and return for sign-off.

**Reply examples:**  
- `OWNER SIGN-OFF 07 launch: approved`  
- `OWNER 07 gallery: target system Photo Picker` / `target in-app gallery`
