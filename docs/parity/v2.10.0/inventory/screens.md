# Screen × state inventory — Android v2.10.0 baseline

**Scope:** production product surfaces that Phase B tickets 07/08 must compare.  
**iOS/Desktop:** listed only as A5 edge cross-ref (alignment is ticket 09).  
**No owner sign-off in this file.**

## Primary Android screens (parity subjects)

### 1. Launch

| State ID | Description | Entry | Locale/theme notes |
|----------|-------------|-------|--------------------|
| `launch-idle` | Empty launch; pick CTA visible | Cold start, no pending share | en/zh |
| `launch-about-entry` | About affordance visible if present | From launch top actions | |
| `launch-gallery-entry` | Navigate to gallery | Tap pick / gallery path | |

**A5 edge:** iOS uses shared `LaunchScreenShell` → PhotosPicker. Desktop has **no** launch screen (window entry).

### 2. Gallery

| State ID | Description | Entry |
|----------|-------------|-------|
| `gallery-empty` | No images / empty library | Fresh emulator without media |
| `gallery-populated` | Grid of thumbnails | With media permission + images |
| `gallery-selected-n` | n images selected; count UI | Multi-select |
| `gallery-dismiss` | Close without confirm | Close affordance |
| `gallery-confirm` | Confirm selection → editor | Done / add |

**A5 edge:** iOS **PhotosPicker-only** (ticket 02). Desktop **FileDialog + drop** (ticket 04).

### 3. Editor

| State ID | Description |
|----------|-------------|
| `editor-text-mode` | Text watermark; preview with tile |
| `editor-image-mode` | Icon watermark after icon pick |
| `editor-option-text` | Text content option / sheet open |
| `editor-option-degree` | Degree slider active |
| `editor-option-tile-repeat` | Tile = REPEAT |
| `editor-option-tile-single` | Tile = CLAMP/Single |
| `editor-option-alpha` | Opacity slider |
| `editor-option-color` | Color palette / custom |
| `editor-option-size` | Text size slider |
| `editor-option-gaps` | H/V gap sliders |
| `editor-option-typeface` | Typeface segment |
| `editor-option-style` | Fill / Stroke |
| `editor-photo-strip` | Multi-photo strip (Android `showPhotoStrip=true`) |
| `editor-templates` | Templates sheet / list open |
| `editor-preview-pinch-or-scroll` | Preview interaction if any |

**A5 edge:** iOS/Desktop share `EditorScreenShell` with `showPhotoStrip=false` on iOS; Desktop no photo strip.

### 4. Save / export

| State ID | Description |
|----------|-------------|
| `export-sheet-open` | Save/export sheet visible |
| `export-format-jpeg` | JPEG selected |
| `export-format-png` | PNG selected |
| `export-quality` | Quality slider (JPEG) |
| `export-progress-k-of-n` | Batch progress `k/n` |
| `export-done` | Success / share / view in gallery actions |
| `export-share` | System share sheet from export result |
| `export-failure` | Error visible (if reproducible) |

**A5 edge:** iOS ShareLink + Save to Photos; Desktop AWT save + share substitute.

### 5. About (Android-only parity subject)

| State ID | Description |
|----------|-------------|
| `about-root` | Full About list + toggles |
| `about-dynamic-color` | Dynamic color toggle state |
| `about-bounds` | Show bounds toggle if present |

**A5 edge:** iOS/Desktop **absent** Phase A (tickets 03/04). Not required for 07/08 Android sign-off unless owner expands 08.

## System / entry edges (document, not full pixel matrix)

| Edge | Notes |
|------|-------|
| Share-in `ACTION_SEND` / `SEND_MULTIPLE` | Cold start into editor with image(s) |
| Crash recovery screen | `MyApp.recoveryMode` → RecoveryScreen |
| Permission denied gallery | Soft-fail to launch |

## Capture priority for tickets 07 / 08

**Ticket 07 (launch + gallery)** — minimum sign-off set:

1. `launch-idle` (en + zh, production then debug)  
2. `gallery-populated` + `gallery-selected-n` (en)  
3. Permission-denied or empty note if not capturable  

**Ticket 08 (editor + export)** — minimum sign-off set:

1. `editor-text-mode` + `editor-option-tile-repeat`  
2. `editor-image-mode` (if icon path available)  
3. One option cluster shot (typeface/style or color)  
4. `export-sheet-open` + `export-done`  
5. Multi-photo strip if production shows it  

## Ready for 07 and 08

B0 inventory + protocol + empty archive are enough to **start capture and sign-off work**.  
Do not claim any screen “matches production” until 07/08 record owner approval with Grok-viewed evidence.
