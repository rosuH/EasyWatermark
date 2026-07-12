# Production vs debug — en / dark (2026-07-12)

**Device:** emulator-5554 (`sdk_gphone64_arm64`)  
**Font scale:** 1.0  
**Locale:** en-US  
**Apps:** production `me.rosuh.easywatermark` v2.10.0 (`MainActivity`) · debug `me.rosuh.easywatermark.debug` (`ComposeMainActivity`)  
**Reviewer:** Grok (opened each PNG; not byte-size review)  
**Owner sign-off:** **not claimed** — see punch-list

## Capture pairs

| State | Production | Debug |
|-------|------------|-------|
| Launch idle | `production/en/dark/launch-idle.png` | `debug/en/dark/launch-idle.png` |
| Gallery / pick | `production/en/dark/gallery-or-picker.png` (system Photo Picker) | `debug/en/dark/gallery-or-picker.png` (in-app “Choose picture”) |
| Gallery selected | `production/en/dark/picker-selected.png` | `debug/en/dark/gallery-selected.png` |
| Editor text mode | `production/en/dark/editor-text-mode.png` | `debug/en/dark/editor-text-mode.png` |
| Export sheet | `production/en/dark/export-sheet-open.png` | `debug/en/dark/export-sheet-open.png` |

## Grok visual findings

### Launch — near match

| Observation | Prod | Debug |
|-------------|------|-------|
| Dark full-bleed background | yes | yes |
| Center logo (square + diagonals) | pink/lavender tint | cooler blue/grey tint |
| “Choose Images” filled button | light purple | light purple |
| About entry | bottom (i) icon | bottom (i) icon (layout also exposes About) |

**Verdict (agent):** structurally close. **Logo color tint** is a visible delta. Owner may accept or request token alignment.

### Gallery — path mismatch (P0) — **owner policy recorded 2026-07-12**

| Observation | Prod (this capture session) | Debug |
|-------------|----------------------------|-------|
| After “Choose Images” | **System Photo Picker** (`com.google.android.photopicker`) with Photos/Albums chips + privacy banner | **In-app GalleryDialog** title **“Choose picture”**, X close, search icon, 4-col grid with empty selection circles |
| Multi-select confirm | system **Done** + count | bottom floating **✓ 1** / add |

**Owner product policy (binding):** Android **prioritizes in-app gallery**; **top-right** gallery action opens **system Photo Picker**. iOS/Desktop default to system picker / file dialog. See `protocol/image-pick-policy.md`.

**Verdict (agent):** Debug primary path **matches product policy**. Production capture of system picker as primary may be API/device residual or legacy entry drift — **re-verify production** under same permission grants; do **not** “fix” debug by making system picker primary. Secondary pair still needed: gallery top-right → system Photo Picker on both apps.

### Editor — large deltas (P0)

| Observation | Prod | Debug |
|-------------|------|-------|
| Top-leading | App logo mark | **Back arrow** |
| Watermark text (persisted state) | `👋 DO NOT REDISTRIBUTE` dense **amber** diagonal tile | `S4d-254 smoke` sparse **lime green** (device prefs pollution) |
| Content controls | Inline text field under photo strip thumb | Text field not visible on Content tab; templates icon bottom-right |
| Bottom chrome | Text selected, Icon, Content/Style/Layout | Same tabs; Text/Icon less clearly selected |
| Photo strip | Single thumb under preview | Single thumb under preview |

**Verdict (agent):** chrome structure similar (Content/Style/Layout + Text/Icon), but **preview density/color/text**, **top bar leading**, and **content editing surface** differ. Reset debug watermark prefs before fair re-capture. Still expect remaining layout deltas after reset.

### Export sheet — structural similar, polish deltas (P1)

| Observation | Prod | Debug |
|-------------|------|-------|
| Sheet over dimmed editor | yes (preview still visible above) | full-screen sheet, little/no editor peek |
| Format JPEG dropdown | yes | yes |
| Quality 80 | slider thumb **right** (~80%) | **value 80 but thumb visually near left** — slider presentation bug / wrong visual |
| Export list 0/1 + thumb | yes | yes |
| CTA “Export to the album” | full-width light purple | full-width light purple |

**Verdict (agent):** same product controls; **quality slider visual** and **sheet overlay chrome** need work before owner pass.

## Punch-list (for owner — no self-sign)

### Ticket 07 (launch / gallery)

1. **[P1]** Launch logo color tint (prod pinker vs debug cooler).  
2. **[P0]** Gallery entry path: prod system Photo Picker vs debug in-app “Choose picture”. Owner choose target.  
3. Re-capture after gallery decision; archive multi-select confirm pair under same path.  
4. Optional: zh locale pairs (not yet captured).

### Ticket 08 (editor / export)

1. **[P0]** Reset debug DataStore watermark defaults to production-like text/color/tile before re-compare (current device has `S4d-254 smoke` + green).  
2. **[P0]** Editor top-leading: logo vs back arrow.  
3. **[P0]** Content tab: restore production-like inline text editing surface / density of tiled preview.  
4. **[P1]** Export sheet: quality slider thumb position vs value; sheet should dim preview like production.  
5. Capture save progress / done states after open sheet matches.  
6. Optional: Style/Layout tabs, icon mode, zh locale.

## Explicit non-claims

- No owner sign-off recorded for any screen.  
- No Phase B code polish in this capture pass.  
- No renderer policy change; Android production raster remains native.
