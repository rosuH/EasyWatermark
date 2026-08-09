# ADR-0025: System-default watermark Text fonts (drop production Noto)

**Status:** Accepted (2026-08-09) · **Supersedes (production font scope of):** ADR-0010 Option A bundle  
**Owner decision:** 2026-08-09 — 字体改为系统默认

## Context

ADR-0010 (and S4d-15/18) chose a production Latin+CJK Noto bundle so Desktop/iOS Skia text shared a cross-platform face with Android. That shipped as:

- `shared/src/desktopMain/resources/fonts/` (Noto Sans + Noto Sans SC)
- `iosApp/iosApp/Resources/Fonts/` (same faces, Xcode Copy Bundle Resources)

Package-size research (2026-08-09) showed **NotoSansSC ~7.9 MB + NotoSans Latin ~602 KB** as the largest *controllable* install-size lever on iOS/Desktop. Android production already rastered Text mode with `FontFamily.Default` (no Play Noto payload). Owner locked: production watermarks use **system default fonts**.

## Decision

1. **Production Text mode** on Android, Desktop, and iOS resolves type via Compose **`FontFamily.Default`** (or pipeline `fontFamily = null` → platform resolver default). No multi-MB face files in product bundles.
2. **Image mode** still ignores `FontFamily` (unchanged).
3. **`TextTypeface` {Normal, Italic, Bold, BoldItalic}** stays a product control; weight/style map to Compose `FontWeight`/`FontStyle`. Real faces are not bundled — platforms **synthesize** bold/italic (already true on iOS system-font goldens).
4. **Test-only Noto** may remain under `desktopTest` / `androidTest` assets for CJK-capable goldens and parity harnesses. It must **not** ship in Play APK, iOS app, or Desktop distributable main resources.
5. **CJK coverage** is an OS/language-pack property, not an app asset. Blank or tofu glyphs on a host without CJK fonts are an environment limit, not a product regression against this ADR. Optional font download is out of scope (offline/privacy app).
6. **Golden policy** (ADR-0010 two-tier / ADR-0010 c2 delta) is unchanged: no claim of byte-parity across platforms; Desktop/iOS gates stay perceptual/stability. Production no longer asserts “bundled CJK fallback engaged.”

## Considered options (rejected)

| Option | Why not |
| --- | --- |
| Keep full Noto in production | Owner rejected size cost after research |
| Subset / variable Noto only | Still a product payload + maintenance; owner chose system default |
| Download fonts on demand | Breaks offline/privacy posture; out of scope |

## Consequences

- **Size win:** ~8.5 MB removed from iOS Resources and Desktop main classpath (each).
- **Visual delta:** Text watermarks follow the host UI/system font (San Francisco / Roboto / JVM logical fonts, plus OS CJK fallbacks when installed). Cross-platform glyph identity is no longer a product goal.
- **Preview ≡ export font source:** both use the same system-default family on a given platform (no preview-bundled / export-bundled split).
- **IosFontLoader / byte-`Font` bundle path:** no longer the production default. May remain internal for optional tooling or loud-failure tests; product export/preview/bridges must not require Noto files in the app bundle.
- **ADR-0010** remains the source for golden two-tier strategy and sRGB pin; only its **production font bundling** scope is superseded here.

## Glossary delta

- **System-default watermark typeface** — production Text-mode face resolution via `FontFamily.Default` / platform resolver; not a bundled file.
- **Test-only bundled face** — Noto (or other) files under test source sets for goldens; never a release payload.
