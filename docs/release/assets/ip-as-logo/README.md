# EasyWatermark IP-as-logo candidates

Tried [ip-as-logo-skill](https://github.com/s1dashu/ip-as-logo-skill) on `feat/migrate_to_compose`. This folder is a **review set only**. Shipping Android / iOS / Desktop icons are unchanged.

Current mark (for comparison): black field + centered yellow-lime slashed square. Brand tokens used as the default three-color map:

| Role | Color | Source |
| --- | --- | --- |
| Background | `#1D1B16` charcoal | `md_theme_dark_background` |
| IP 1 | `#FFDE32` gold | Material seed |
| IP 2 | `#FFF4D6` cream parchment | warm off-white preferred by the skill |

## Directions

The skill asks for three different IP subjects when none is specified. Each one is tied to a product attribute, not an arbitrary animal.

| ID | Subject | Product connection | Defining silhouette |
| --- | --- | --- | --- |
| A | Wax stamp-seal | Watermark as an official mark on a photo | Circular head + one thick rounded diagonal slash |
| B | Rounded ghost | README “ghostly” watermark / mischievous protection | Blob ghost + slash plate or visor |
| C | Shield turtle | Offline privacy guardian vs the “BAD GUY” | Compact turtle + one large shell |

Default batch: two independent variants per direction (`A1`/`A2`, `B1`/`B2`, `C1`/`C2`).

## Generation

- **Generator:** Cursor `GenerateImage` (instruction-following image model)
- **Constraint delivery:** `main-prompt constraints` (no dedicated `negative_prompt` parameter)
- **Constraint text:** no text/watermark; no borders, frames, cards, or App-icon masks; one mascot only; thick rounded contours; no fragile lines or sharp tips; graphic and softly dimensional; no photorealistic materials, strong 3D, or external cast shadows
- **Requested size:** 1536×1536 square. **Native output:** 1024×1024 opaque PNG (preserved, not resampled)
- **Background mode:** opaque full-bleed charcoal, all four corners present
- Round 1 produced clay-like 3D toys. One targeted retry flattened the finish. Masters in `candidates/` are **round 2**. Round 1 is not committed (still attached on the PR).

## Candidates (round 2)

| Label | Path | Verdict | Findings |
| --- | --- | --- | --- |
| **A1** | [`candidates/A1-stamp.png`](candidates/A1-stamp.png) | **Recommended** | Flat circular gold seal, cream slash, two eyes + mouth, cropped large. Holds at 32×32. Missing requested micro-volume (reads almost pure flat). Crop is bottom/right rather than lower-left. Facial marks are darker gold (same family). |
| **A2** | [`candidates/A2-stamp.png`](candidates/A2-stamp.png) | Not recommended | Scalloped seal edge adds decorative complexity. Face reads as a `%` which is clever but busy at small size. Faint inner-circle shadow. |
| **B1** | [`candidates/B1-ghost.png`](candidates/B1-ghost.png) | Borderline | Gold ghost + cream sash is on-brief and readable at 32×32, but still slightly pillowy / 3D. One arm reads as a wave. |
| **B2** | [`candidates/B2-ghost.png`](candidates/B2-ghost.png) | Not recommended | Horizontal mask instead of a diagonal slash. Waving arm is illustration, not a symbol. Face collapses at 32×32. |
| **C1** | [`candidates/C1-turtle.png`](candidates/C1-turtle.png) | Borderline | Clean three-color split and readable at 32×32, but centered like a sticker and barely a turtle (blob + dome). |
| **C2** | [`candidates/C2-turtle.png`](candidates/C2-turtle.png) | Not recommended | Still two masses (head + separate cream disc). Soft 3D volume. Mushy at 32×32. |

`thumbs32/` is an evaluation downsample only. Do not ship those files.

## What this does *not* do

No replacement of:

- `iosApp/.../AppIcon.appiconset/`
- `app/src/main/res/mipmap-*` / `ic_launcher_foreground.xml`
- `desktopApp/icons/` or `ic_app_icon_window.png`
- toolbar / About logos

Pick a candidate (or a direction to refine) and a follow-up can run `desktopApp/icons/generate_app_icon.py` plus the Android adaptive-icon path.
