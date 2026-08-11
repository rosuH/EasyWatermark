# ADR-0027: Wallpaper dynamic color + Content editor theme

**Status:** Accepted (2026-08-11)  
**Owner decision:** grill-with-docs on cross-platform dynamic color (research `docs/superpowers/research/2026-08-10-cross-platform-dynamic-color.md`)  
**Supersedes in part:** ADR-0014 palette “keep/drop” product path for photo-driven chrome (S4d-41 drop remains for dormant Palette plumbing; **feature reopened** here under a different design)  
**Related:** ADR-0007 (`DynamicColorCapability` = wallpaper gate only), ADR-0011 (forced-dark parity)

## Context

Production Android (v2.10.0) had two independent color systems that were often conflated as “动态颜色”:

1. **Wallpaper Material You** — system wallpaper seed → whole-app M3 tokens (`:cmonet` / `DynamicColors` / Compose `dynamic*ColorScheme`), gated by OEM allowlist + About force flag.
2. **Photo Palette chrome** — `androidx.palette` from the preview bitmap → editor background / some panels (not a full scheme). Dormant in Compose, then **removed** (S4d-41 / ADR-0014 addendum).

CMP hosts showed an About “Force Dynamic Color” control that was a **no-op** on iOS/Desktop. iOS has no public wallpaper or system-accent seed API for third-party apps; Desktop accent is possible on Mac/Win but was **rejected** for this product. A multiplatform “Material You everywhere” story is false.

Owner wants both a real cross-platform dynamic feel (**from the open photo**) and honest Android wallpaper MY, without thin background-only tint.

## Decision

### 1. Two named systems (do not merge)

| Term | Meaning |
| --- | --- |
| **Wallpaper dynamic color** | Android-only whole-app scheme from **system wallpaper**. |
| **Content editor theme** | Full Editor-session M3 `ColorScheme` from the **currently selected photo**. |

`DynamicColorCapability` remains the **wallpaper** gate only. Content theme is a separate preference / application path.

_Avoid:_ one “dynamic color” flag meaning both; calling iOS/Desktop “Material You.”

### 2. Wallpaper dynamic color (Android)

- Keep shipping wallpaper MY via system APIs (`DynamicColors` / `dynamicDarkColorScheme` path; `:cmonet` may remain as indirection until absorption).
- **Remove OEM manufacturer allowlist** — availability follows `DynamicColors.isDynamicColorAvailable()` (and API level), not a hand-maintained brand list.
- Replace “force past OEM” semantics with an explicit **follow wallpaper** user preference (on → use system dynamic scheme when available; off → static brand scheme).
- **iOS / Desktop:** no wallpaper dynamic color; no system-accent theming in this product.

### 3. Content editor theme (all platforms)

- **Scope:** entire **Editor** surface (preview + inspector / supporting pane), a **full** dark M3 `ColorScheme` — not a single background wash.
- **Seed:** currently selected session image (filmstrip focus). Change selection → recompute (debounce + short transition).
- **Default:** **on**.
- **Priority:** while Editor is shown and follow-photo is on, **content theme outranks** wallpaper dynamic color on Android for that surface.
- **Brand:** **pure seed** — no harmonize toward brand amber `#FFB800` inside Editor. Amber returns when content theme is off or user leaves Editor.
- **Light/dark:** content scheme is always the **dark** recipe (forced-dark parity). Seed affects hue/chroma roles, not a sudden light Editor.
- **Fallback:** no image / decode or seed failure → static brand scheme (no sticky last-seed cache required).
- **Leave Editor** (Launch, About, …) → static brand; Android may again show wallpaper MY if follow-wallpaper is on.
- **Libraries:** generate scheme with **MaterialKolor** (MCU KMP). Do **not** reintroduce `androidx.palette` as the product path. Do **not** replace Android wallpaper consumption with MaterialKolor.

### 4. Preferences / About UX

| Preference | Android | iOS / Desktop | Default |
| --- | --- | --- | --- |
| Follow wallpaper | Yes | Hidden | Align with “use MY when system available” product default (implementation may map legacy `dynamic_color_force` carefully) |
| Follow current photo | Yes | Yes | **On** |

Copy must not claim Material You / wallpaper on non-Android.

### 5. Implementation stance (this ADR does not ship code)

- Docs + product policy only until a named implementation slice.
- J4: one dependency slice when adding MaterialKolor.
- Persist preferences in the existing prefs style (platform DataStore / current About storage) without inventing a second shadow store without cause.
- Visual gates: Android wallpaper path must not regress; Editor photo theme is a **new** baseline (screenshots), not a silent v2.10.0 pixel match for Editor chrome.

## Considered options (rejected)

| Option | Why not |
| --- | --- |
| Thin Palette bg only (historical B) | Owner: not enough; want full Editor theme |
| iOS/Desktop system accent | iOS: no public accent/wallpaper seed; Desktop accent optional cost rejected — photo only |
| OS wallpaper quantize on Desktop | Fragile, multi-monitor, privacy surface |
| Content theme = whole app including About | Over-tint; leave non-Editor on brand/wallpaper |
| Harmonize seed toward brand amber in Editor | Owner chose pure content immersion |
| Keep OEM allowlist | Owner: follow system availability only |
| Fold content into `DynamicColorCapability` | Overloads wallpaper gate; confuses platforms |
| Silent restore of `androidx.palette` | S4d-41 was correct for dead wiring; new design is MCU scheme |

## Consequences

- ADR-0014 S4d-41 “drop dormant Palette” stands for the old plumbing; **photo theming returns only as Content editor theme** per this ADR.
- Android wallpaper behavior **changes** vs v2.10.0 (no OEM list) — requires product/release note, not silent parity claim.
- iOS/Desktop About “force dynamic color” no-ops must be replaced by **follow photo** (and hide wallpaper controls).
- Glossary updated in `docs/CONTEXT.md` (`Wallpaper dynamic color`, `Content editor theme`, narrowed `DynamicColorCapability`).
- Implementation work is a follow-up milestone (capability split, MaterialKolor, Editor theme host, preference migration).
