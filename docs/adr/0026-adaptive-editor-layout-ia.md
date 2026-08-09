# ADR-0026: Adaptive editor layout IA (Supporting-pane + optional Three-zone)

**Status:** Accepted (2026-08-09)  
**Owner decision:** grill-with-docs Round 1–2 + UX demo sign-off (“符合预期”)  
**UX demo:** `docs/superpowers/research/easywatermark-adaptive-layout-ux-demo.html`  
**Research:** `docs/superpowers/research/2026-08-09-large-screen-layout-synthesis.md`

## Context

The shared editor already has a hand-rolled width class (`EditorLayoutClass`) with dual-pane only at **≥1024 dp** (Expanded) and a vertical stack for Compact/Medium. Desktop fullscreen showed supporting-pane polish issues (letterbox padding, right-rail clip). Official Window Size Class treats Expanded width as **≥840 dp**; media-tool guidance maps to **supporting pane** (preview primary, tools secondary), not list-detail.

Product research compared three schemes: **A** Supporting Pane Studio, **B** Immersive Canvas density, **C** Three-Zone Batch. Owner locked a hybrid IA before implementation so breakpoints, Scheme C scope, and collapse rules do not thrash during CMP layout work.

## Decision

1. **Product default (H1):** Hybrid **A/B**. Compact prioritizes canvas height (B density / stacked chrome). Medium+ default chrome is **Supporting Pane Studio (A)** when width allows dual-pane.
2. **Dual-pane threshold (D1):** Supporting-pane (preview | inspector) starts at window width **≥840 dp**, aligned with WSC Expanded. Today’s **1024** Expanded gate is superseded for product policy.
3. **Medium (M1):** **600–839 dp** uses the **same vertical stack skeleton as Compact** (preview → filmstrip → bottom controls). No forced narrow side pane on Medium.
4. **Scheme C in-scope (C3 / C-W1):** **Three-zone batch** is in this period and may ship in parallel with A polish. Auto-activate at **≥1440 dp** (not at 1200, not Desktop-only, not a user mode toggle for v1).
5. **C left zone (C-L4):** Session **image library** only. Templates stay on the existing sheet/entry; not first-class left-rail content in the first C cut.
6. **C collapse (C-F1):** Shrinking below the C threshold **hard-cuts to A** (left zone gone). No half-open icon rail. Selection and watermark config follow **session**, not pane lifetime.
7. **Filmstrip (F1):** Always owned by the **primary preview pane** (under canvas). Does not span under the inspector; does not move into the C left library.
8. **Desktop fullscreen (P1):** Fix **A quality** for all Expanded widths (including fullscreen). Fullscreen is **not** a Scheme C trigger; C still requires ≥1440 dp.
9. **Fold (K1):** No `FoldingFeature` / tabletop / book posture in this period. Width (and existing height constraints for dual-pane) only.
10. **API (S1):** Keep **hand-rolled** layout class (+ a third “wide/three-zone” band as needed). Do **not** require Material3 `SupportingPaneScaffold` or Nav3 `SupportingPaneSceneStrategy` for this editor surface in this period (single-scene tool chrome, not multi-destination pane navigation).
11. **Terminology (T1):** Domain name **Supporting-pane editor**; code may keep `EditorLayoutClass` (or evolve names) without forcing glossary words into identifiers.

## Considered options (rejected)

| Option | Why not |
| --- | --- |
| Dual-pane only at 1024 | Leaves many tablet landscape windows single-column vs WSC |
| Medium half-pane / forced ≤280 side rail | Crushes canvas ~700 dp; deferred complexity |
| Scheme C deferred (C1/C2) | Owner chose C3 — design+implement in this period |
| C at ≥1200 or always-on Desktop | Too early / platform-skewed; stick to 1440 power width |
| C left = templates or dual Tab v1 | Heavier nav; templates already have sheet |
| Soft C collapse (rail / sheet merge) | Two half-layouts to maintain |
| Fullscreen → special third layout or force C | Masks A bugs; diverges from width policy |
| Adaptive scaffold / Nav3 SceneStrategy now | Editor is one tool scene; scaffold tax without multi-pane nav state |

## Consequences

- **Implementation spine:** (1) P0 A polish + Expanded@840, (2) Medium stays stack, (3) Wide@1440 three-zone + session image list, (4) fold/scaffold later.
- **Code today vs policy:** `EditorLayoutClass` Expanded at 1024 and no three-zone band are **known gaps** until migrated; do not treat 1024 as the product decision after this ADR.
- **Gallery** remains feed/grid adaptive (existing min-cell policy); this ADR does not redefine gallery as list-detail.
- **iOS multi-window** remains out of scope (ADR-0020); adaptive rules apply to the single shared editor window size.
- **Docs:** glossary terms in `docs/CONTEXT.md`; interactive morph demo under `docs/superpowers/research/`.

## Glossary delta

- **Supporting-pane editor** — editor chrome: primary preview + secondary tools pane (official supporting-pane mapping).
- **Three-zone batch editor** — wide layout: session images | canvas | inspector (≥1440 dp).
- **Editor layout class** — width-derived layout band driving stack vs supporting-pane vs three-zone (implementation may name Compact/Medium/Expanded/Wide).
