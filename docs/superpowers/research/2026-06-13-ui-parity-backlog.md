# UI Parity Backlog — Compose branch vs Production v2.10.0

**Date:** 2026-06-13 · **Method:** side-by-side install on one emulator (Medium_Phone, 1080×2400), same journey driven in both builds, 8 canonical screens captured each, multimodal per-screen comparison (workflow `ui-parity-audit`, run wf_d279ab26-867).
**Baseline:** production v2.10.0 (`me.rosuh.easywatermark`) — the source of truth per ADR-0011. Compose branch debug (`me.rosuh.easywatermark.debug`, versionName 2.9.6-stale).
**Screenshots:** `docs/superpowers/research/parity-shots/{prod,compose}/<screen>.png` (untracked, 27 MB raw — compress before committing, or keep local).
**Screens compared:** launch, editor, editor-tab-content, editor-tab-style, editor-tab-layout, dialog-text-edit, sheet-save, screen-about — 8/8 paired, none missing.

## Cross-cutting clusters (fix once, benefits every screen)

### P0-A — Theme: production is DARK with amber accent; Compose renders light/cream with olive buttons
The single biggest deviation, visible on all 8 screens. Production: near-black surfaces (~#1a1a1a), bright amber/yellow brand accent (buttons, slider thumb, Confirm), warm dynamic tints on supported devices. Compose branch: cream/off-white Material3 light scheme, dark-olive primary, follows system theme.
**Action:** extract production color tokens from master `themes.xml`/`styles.xml` into the Compose `ColorScheme` (dark-first, amber accent); decide follow-system vs forced-dark consciously (production behavior is the spec — verify what v2.10.0 does in system-light mode before coding); dynamic-color capability stays per ADR-0007.

### P0-B — Editor thumbnail filmstrip is missing entirely
Production shows a horizontal photo-thumbnail strip (multi-image selector) between the preview and the panel on every editor tab. Compose has a blank gap there. This is a feature affordance, not styling — flagged blocker on 4 screens. Relates to the earlier finding that Compose "add more images" is replace-not-append.
**Action:** build the Compose filmstrip component (selected state, tap-to-switch), wire to `imageList`; close the layout gap (P1-D).

### P1-C — Editor top bar: back-arrow replaced the app logo
Production: brand logo leading, no back arrow (editor is the root). Compose: back chevron, no logo.
**Action:** restore logo-leading top bar in the editor for parity (navigation semantics decision: editor-as-root vs pushed — match production).

### P1-D — Bottom panel density/spacing
Compose preview canvas is taller, leaving a large blank band; production stacks filmstrip → text row → type toggles → tabs tightly. Mostly resolves with P0-B + spacing pass.

## Per-screen items

| Screen | Sev | Deviation | Action |
|---|---|---|---|
| launch | major | "Choose Images" is a huge pill in olive; production is near-rect amber | shape+color tokens (P0-A) |
| launch | minor | logo slash stripes render light; production dark | asset/tint check |
| editor | ~~blocker~~ filmstrip DONE | ~~filmstrip absent~~ → `PhotoList` gate `size > 1` → `isNotEmpty()`; single-image strip now shows (emulator-verified). Text row render: fixed separately (onTextChange wired). |
| editor-tab-content | blocker/minor | same as editor; emoji in default text read as 🤙 (prod) vs 👋 (branch) | Verify-2: diff master vs branch default string |
| editor-tab-style | major→minor | "Repeat/Single" radio row absent — BUT Compose has a 2-segment strip at panel top, likely the SAME TileMode control restyled | align component style to production radio-row look, or sign off the segmented style via ADR; don't double-build |
| editor-tab-style | minor | 6 icons crammed in one row (prod: scrollable, 3 visible); "Degree" label truncated to "Ang" | scrollable row + full label |
| editor-tab-layout | major | gap slider's yellow thumb invisible/missing; track thin | slider theming (P0-A tokens) |
| dialog-text-edit | blocker | production's "Edit watermark" modal sheet (title + framed field + amber Confirm + multiline icon) is gone — Compose edits inline above the IME | rebuild the Compose text-edit sheet for parity (M3 item) |
| sheet-save | ~~blocker~~ DONE (code) | ~~export list shows "1 image(s) selected" placeholder~~ → now a Coil thumbnail LazyRow (`SaveExportSheet imageUris`); source-image thumbs for now, watermarked thumbs await C2 renderer; UI verify pending next emulator batch |
| sheet-save | ~~major~~ NOT A BUG | ~~Quality shows 40 on fresh install~~ → fresh `pm clear` install shows 80 (correct). Audit's 40 was residual DataStore from a prior slider drag kept by `install -r`. Closed. |
| sheet-save | major | sheet is full-screen; production peeks partially | sheet expansion behavior |
| sheet-save | major | extra "View in gallery" link not in production | remove or get sign-off (ADR-0011 rule) |
| sheet-save | cosmetic | drag handle visible (prod none); heading typography heavier | polish pass |
| screen-about | major* | version reads 2.9.6 vs 2.10.0 | *stale branch versionName — resolves at rebase/version bump, no UI work |
| screen-about | — | legacy Activity in both; everything else matches | none (C1.4 migrates it later, with prod screenshots as baseline) |

## Matches well (don't touch)
Watermark tiling render (angle/density/color) matches across builds; 3-tab structure, Text/Icon toggles, top-right action trio, format dropdown + conditional quality row + "Export list(0/1)" header + full-width export button all structurally correct; About screen near-identical (legacy both).

## Verify list (before coding)
1. **Text entry row on Content tab:** capture notes say it exists as a tappable row; comparator couldn't see it in screenshots. Confirm rendering state on device.
2. **Default watermark emoji:** comparator read production as 🤙, branch code has 👋 (`WaterMark.kt:34`); default text comes from a string resource via `MyApp.getString`. Diff master vs branch `strings.xml` + `WaterMark` default before "fixing".
3. **Production light-mode behavior:** is v2.10.0 forced-dark or system-following? Drives P0-A scope.
4. **Segmented strip on Style tab:** confirm it is the TileMode control (not a template/preset selector).

## Status update (2026-06-13, Phase H batch 1)

- **P0-A RESOLVED.** Root cause was sharper than "theme tokens": the palette already existed in `Color.kt`; `AppTheme` defaulted to `isSystemInDarkTheme()` while production forces dark (`Theme.Material3.Dark`, no DayNight). Fixed: `darkTheme = true` default + ~15 dark tokens aligned to master `colors.xml` exactly (visible ones: surface `#15130E→#1D1B16`, onSurface `#CBC6BD→#E8E2D9`, primary `E5C50E→E4C50D`). Launch screen re-captured in FORCED-LIGHT system mode — matches production baseline (`parity-shots/compose/launch-fixed.png`).
- **Launch button: fixed.** Production brand language is sharp corners (`ShapeAppearance.App.SmallComponent = 0dp`); added `shape = RectangleShape` (consistent with SaveExportSheet's existing convention). Color resolved by P0-A. Logo stripe color also resolved by P0-A (was theme-derived tint, not an asset issue).
- **Quality default 40 vs 80: DOWNGRADED to suspected audit artifact.** Code path is solidly 80 (`DEFAULT_COMPRESS_LEVEL=80`, DataStore `?: 80`, %20 coercion; slider 20..100/steps=3 is correct). Only a persisted 40 can produce the screenshot — the capture agent likely tapped the slider (~25% position ⇒ 40) during its blind coordinate taps. Re-verify on a clean install before treating as a bug.
- **Verify items closed:** (1) Content-tab text row EXISTS but renders without the watermark text — remains an open work item (P1); (2) emoji: NO deviation, both 👋 (comparator misread); new small debt found: default text duplicated in `WaterMark.kt:34` (hardcoded) and `strings.xml` via repo — unify later; (3) production is forced-dark — confirmed and now mirrored; (4) Style-tab segmented strip IS the TileMode control (M3 `SingleChoiceSegmentedButtonRow`) vs production radio row — **needs developer sign-off**: keep segmented (recommended; modern equivalent, amend ADR-0014) or revert to radio for strict parity.
- Editor/save-sheet color deviations should now largely self-resolve via P0-A — re-verify in the next audit pass before working the remaining per-screen styling items.

## Real-device verification (2026-06-13, andromeld → Galaxy S22+)

- **Theme fix verified on real hardware.** Launch screen captured on the S22+ via AndroMeld: forced-dark surfaces match production; with `dynamicColor = CMonet.isDynamicColorAvailable()` now wired, the device's Material You wallpaper palette flows through (logo + button render in the system's pink/lilac accent — `parity-shots/compose/s22-launch-dynamic.png`). This is the production behavior the static-light branch was missing.
- **Dynamic-color bridge added** (`ComposeMainActivity`): `AppTheme(dynamicColor = CMonet.isDynamicColorAvailable())` — Android keeps the production cmonet/DynamicColors gate until ADR-0007's capability replaces it.
- **Content-tab text input fixed** (`EditorScreen` Text branch): `onTextChange` was `{}` (typing did nothing); now routes to `onChange(item, it)` → VM `updateText`. Build green; end-to-end real-device confirmation pending (see tooling note).
- **AndroMeld tooling notes (important for future device work):**
  - `andromeld.ui.click_element` requires a `stateSignature` arg (from the latest `ui.get_state`) — it's a freshness/anti-race guard.
  - **Check `screen.isStale` + `frameId` on every `screen.observe`.** A frozen mirror (device asleep/locked) returns `isStale:true` with a stale frame; taps may still land but are invisible. Wake/confirm the device session before trusting a screenshot. Do not force-wake the user's personal phone for gallery flows (privacy).
  - System photo-picker / gallery selection on the real device is gated/awkward under mirroring — **editor-internal parity (anything requiring a loaded image) is better verified on the emulator** (adb can push images + drive the picker), reserving andromeld for launch/about/display-only screens on real hardware.

## Emulator verification (2026-06-13, editor-internal via adb)

End-to-end editor flow driven on the headless Medium_Phone emulator (API 29, adb — real device's system picker is gated under andromeld mirroring):

- **Theme fix self-heals the whole editor.** After loading an image, the editor's top bar, bottom control panel, Text/Icon toggles, and tab row are all dark with amber accent (`parity-shots/compose/emu-editor-themed.png`) — the light/cream deviations the audit flagged on editor/editor-tab-*/sheet-save screens were ALL downstream of P0-A and are now resolved in one fix. Confirms the backlog prediction. P0-A is now double-verified: real device (launch, andromeld) + emulator (editor, adb).
- **Content-tab text input fix confirmed working.** Typing into the watermark text field updates the tiled preview live (`parity-shots/compose/emu-text-input-works.png` — appended `_PARITY`, watermark re-tiled). Before the fix `onTextChange` was `{}` and typing did nothing. The `onTextChange → VM.updateText` round-trip works.
- **GalleryDialog confirm-button note:** the amber "✓ N" confirm needs a reliable tap on its exact bounds (`[435,1959][645,2106]`); a near-miss silently no-ops. Worth a larger touch target / content-desc for testability (minor).
- **Still open (re-confirmed against production):** P0-B filmstrip (editor still has the empty band where production shows the thumbnail strip); text-edit modal sheet (production opens a titled sheet w/ Confirm; Compose edits inline); save-sheet export-list thumbnails + sheet expansion + "View in gallery" link; top-bar logo vs back-arrow; TileMode segmented-vs-radio sign-off; quality-default re-check on clean install.

## Suggested C1 work order (parity stream)
1. P0-A theme tokens (unblocks visual judgment of everything else)
2. sheet-save quality-default bug (small, isolated, real)
3. P0-B filmstrip component
4. dialog-text-edit parity sheet
5. P1-C top bar + P1-D spacing + style-tab polish (segmented→radio alignment, slider theming, labels)
6. sheet-save remaining (thumbnails, expansion, extra link)
