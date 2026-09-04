# Capture protocol — Android production vs debug

## Hard rules

1. **Source of truth is production v2.10.0 only** (`me.rosuh.easywatermark`), not this branch’s Compose drafts (ADR-0011).
2. **Same device/emulator** for every pair. Prefer one fixed AVD (e.g. Pixel-class phone) for the whole Phase B archive.
3. **Grok (or human) must open images/recordings** and write concrete observations. File size / green tests ≠ visual proof.
4. Capture **production first**, then debug, without changing system locale/theme/font between the pair.
5. Grant **media permission** (`READ_MEDIA_IMAGES` / legacy storage) before gallery/editor flows; note permission state in the shot log.
6. Use **representative fixture images** (same assets for production and debug). Prefer a small set under `docs/parity/v2.10.0/fixtures/` once added (not required for B0 inventory).

## Control matrix (minimum for tickets 07/08)

| Control | Values | Notes |
|---------|--------|-------|
| Locale | `en`, `zh` | System language; restart app after change |
| Theme | production is **forced dark** (ADR / prior parity research); still capture “system light + system dark” if debug differs | Document any forced-dark vs follow-system delta |
| Font scale | **1.0** default; optional **1.3** stress later | `settings put system font_scale 1.0` |
| Orientation | Portrait | Landscape only if product supports |
| Images | Fixed fixture set (color blocks + 1 photo) | Same bytes for both apps |

## Naming convention

```
{app}/{locale}/{theme}/{screen}-{state}-{optional-note}.png
```

Examples:

- `production/en/dark/launch-idle.png`
- `production/en/dark/gallery-multi-selected-2.png`
- `debug/zh/dark/editor-text-mode-repeat.png`
- `debug/en/dark/export-saving-2of3.png`

Recordings: same stem with `.mp4` under the same folder.

## Capture tools (preferred order)

1. `android screenshot` / `adb exec-out screencap -p` → write into the path above  
2. AndroMeld side-by-side when available  
3. Manual emulator camera only as last resort  

After each capture: **open the PNG**, confirm it is the intended screen (not lock screen / notification shade / wrong app).

## Permission checklist

| API | Permission | When |
|-----|------------|------|
| 33+ | `READ_MEDIA_IMAGES` | Before gallery / share-in |
| 29–32 | `READ_EXTERNAL_STORAGE` | Before gallery |
| Save path | none special on 29+ (MediaStore) | Note if Save fails |

## What B0 does **not** require

- Full matrix filled (that is 07/08 capture work).
- Owner “looks good” sign-off (07/08).
- iOS/Desktop shots (09).
- Code polish to chase pixels (unless capture is blocked).
