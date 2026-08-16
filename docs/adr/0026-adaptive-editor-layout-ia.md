# ADR-0026: Adaptive editor layout IA (Supporting-pane + optional Three-zone)

**Status:** Accepted (2026-08-09); **amended 2026-08-10** (drop three-zone left rail); **amended 2026-08-16** (dual-pane threshold 840 → 800 dp)  
**Owner decision:** grill-with-docs Round 1–2 + UX demo sign-off (“符合预期”); 2026-08-10 live Desktop review — left session library “毫无意义”, use dual-pane only; 2026-08-16 iOS iPad route B review — lower the dual-pane floor so 11" iPads are not stuck on the phone stack  
**UX demo:** `docs/superpowers/research/easywatermark-adaptive-layout-ux-demo.html`  
**Research:** `docs/superpowers/research/2026-08-09-large-screen-layout-synthesis.md`

## Context

The shared editor already has a hand-rolled width class (`EditorLayoutClass`) with dual-pane only at **≥1024 dp** (Expanded) and a vertical stack for Compact/Medium. Desktop fullscreen showed supporting-pane polish issues (letterbox padding, right-rail clip). Official Window Size Class treats Expanded width as **≥840 dp**; media-tool guidance maps to **supporting pane** (preview primary, tools secondary), not list-detail.

Product research compared three schemes: **A** Supporting Pane Studio, **B** Immersive Canvas density, **C** Three-Zone Batch. Owner locked a hybrid IA before implementation so breakpoints, Scheme C scope, and collapse rules do not thrash during CMP layout work.

## Decision

1. **Product default (H1):** Hybrid **A/B**. Compact prioritizes canvas height (B density / stacked chrome). Medium+ default chrome is **Supporting Pane Studio (A)** when width allows dual-pane.
2. **Dual-pane threshold (D1, amended 2026-08-16):** Supporting-pane (preview | inspector) starts at window width **≥800 dp**. Originally **≥840 dp** to match WSC Expanded; lowered because the entire 11" iPad class lands just under that line in portrait (iPad Air 11" **820 pt**, iPad Pro 11" **834 pt**) and fell back to the phone stack on a physically large tablet. The inspector is a fixed 360 dp rail, so the preview pane still gets ~416 dp at the 800 floor — wider than any phone the Compact path already serves. 800 rather than 820 keeps headroom against safe-area inset consumption; nothing in the iPad lineup sits between iPad mini (744 pt) and 820 pt, so the two values are equivalent for iPad but 800 is the robust one. Today’s **1024** Expanded gate is superseded for product policy.
3. **Medium (M1, amended 2026-08-16):** **600–799 dp** uses the **same vertical stack skeleton as Compact** (preview → filmstrip → bottom controls). No forced narrow side pane on Medium. iPad mini (744 pt portrait) stays on this stack.
4. **Wide band retained for classification only (C-W1 amended):** Window width **≥1440 dp** may still classify as `EditorLayoutClass.Wide`, but **chrome matches Expanded dual-pane** (preview + filmstrip | form inspector). **No third left zone.**
5. **Scheme C three-zone left rail withdrawn (C3 / C-L4 superseded 2026-08-10):** Owner rejected the session-image left library as redundant with the filmstrip (“左侧毫无意义”). Templates stay on the existing sheet/entry. Do **not** reintroduce a left session rail without a new ADR.
6. **Collapse (ex C-F1):** Crossing the 1440 band no longer mounts/dismounts a left pane — dual-pane is continuous for all ≥800 dp. Selection and watermark config follow **session**.
7. **Filmstrip (F1):** Always owned by the **primary preview pane** (under canvas). Sole multi-image switcher on Expanded/Wide; does not span under the inspector.
8. **Desktop fullscreen (P1):** Fix **A quality** for all dual-pane widths (including fullscreen / ≥1440). Fullscreen is **not** a separate layout scheme.
9. **Fold (K1):** No `FoldingFeature` / tabletop / book posture in this period. Width (and existing height constraints for dual-pane) only.
10. **API (S1):** Keep **hand-rolled** layout class (`Compact` / `Medium` / `Expanded` / `Wide` width bands). Do **not** require Material3 `SupportingPaneScaffold` or Nav3 `SupportingPaneSceneStrategy` for this editor surface in this period.
11. **Terminology (T1):** Domain name **Supporting-pane editor**; “Three-zone batch editor” is **historical / withdrawn** for product chrome (see glossary).

## Considered options (rejected)

| Option | Why not |
| --- | --- |
| Dual-pane only at 1024 | Leaves many tablet landscape windows single-column vs WSC |
| Medium half-pane / forced ≤280 side rail | Crushes canvas ~700 dp; deferred complexity |
| Scheme C deferred (C1/C2) | Originally owner chose C3; **C3 left rail later withdrawn 2026-08-10** |
| C at ≥1200 or always-on Desktop | Too early / platform-skewed |
| C left = templates or dual Tab v1 | Heavier nav; templates already have sheet |
| Soft C collapse (rail / sheet merge) | Two half-layouts to maintain |
| Fullscreen → special third layout or force C | Masks A bugs; diverges from width policy |
| Adaptive scaffold / Nav3 SceneStrategy now | Editor is one tool scene; scaffold tax without multi-pane nav state |
| Keep empty left session library at ≥1440 | Owner: redundant with filmstrip; wastes canvas |

## Consequences

- **Implementation spine:** (1) Expanded@800 dual-pane + A polish, (2) Medium stays stack, (3) ≥1440 same dual-pane (Wide class optional), (4) fold/scaffold later.
- **2026-08-10 amendment:** remove `EditorSessionImageLibrary` left rail; filmstrip-only multi-image switch on dual-pane.
- **Gallery** remains feed/grid adaptive (existing min-cell policy); this ADR does not redefine gallery as list-detail.
- **iOS multi-window** remains out of scope (ADR-0020); adaptive rules apply to the single shared editor window size.
- **Docs:** glossary terms in `docs/CONTEXT.md`; interactive morph demo under `docs/superpowers/research/` (demo may still show historical C — product chrome is dual-pane).

## Glossary delta

- **Supporting-pane editor** — editor chrome: primary preview + secondary tools pane (official supporting-pane mapping). Product default for all **≥800 dp** widths.
- **Three-zone batch editor** — **withdrawn** for product chrome (2026-08-10). Was: session images | canvas | inspector at ≥1440. Do not reintroduce without ADR.
- **Editor layout class** — width-derived layout band driving stack vs supporting-pane (implementation may name Compact/Medium/Expanded/Wide; Wide no longer implies a third pane).
