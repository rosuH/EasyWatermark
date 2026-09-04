# Large-screen / tablet / foldable layout synthesis — EasyWatermark

**Date:** 2026-08-09  
**Audience:** Product + eng decision on editor/gallery adaptive IA  
**Visual report:** `~/.agent/diagrams/large-screen-layout-research-report.html`  
**Companion notes:**

- [Compose official BP](./2026-08-09-compose-large-screen-official-bp.md)
- [Competitor layouts](./2026-08-09-competitor-large-screen-layouts.md)

**Method:** parallel research agents + Android Developers docs + adaptive skill + X (@AndroidDev) + GitHub samples references.

---

## 1. Executive recommendation

| Layer | Choice |
|-------|--------|
| **Canonical pattern (official)** | **Supporting pane** (preview = main, tools = supporting) — *not* list-detail for editor chrome |
| **Default product IA** | **Scheme A — Supporting Pane Studio** on Medium+ |
| **Phone density** | **Scheme B — Immersive Canvas** interactions (hide/peek chrome) even if chrome stays stacked |
| **Desktop power** | **Scheme C — Three-Zone Batch** only if batch/templates justify cost (≥ ~1440dp) |
| **Immediate fix** | Align breakpoints toward WSC; polish A (padding, side pane clip); Desktop fullscreen |

**One sentence:** Treat the watermark editor as a **media tool supporting-pane** app: canvas-first on phones, durable side inspector on tablet/desktop, optional three-zone only for batch/desktop power users.

---

## 2. EasyWatermark today (gap)

| Aspect | Current | Gap |
|--------|---------|-----|
| Breakpoints | Compact &lt;600, Medium 600–1024, Expanded ≥**1024** | Official Expanded width starts **840dp** — many tablet landscape windows stay single-column |
| Expanded layout | `preview \| controls` max **360dp** + `fillMaxWidth(0.38f)` | Matches supporting-pane *shape*, weak padding/clip/fullscreen |
| Medium | Same vertical stack as Compact | No half-pane / sheet specialization |
| Fold | No `FoldingFeature` | Tabletop/book unused |
| Dependencies | Hand-rolled `EditorLayoutClass` | No `SupportingPaneScaffold` / Nav3 SceneStrategy |
| Desktop fullscreen | Bare `Window` + Fit + double 12dp pad | Empty letterbox + right rail clip (user repro) |

Gallery already uses adaptive min cell size (`GALLERY_ADAPTIVE_MIN_CELL_DP = 80`) — closer to **feed** pattern.

---

## 3. Official Compose / Android practices (condensed)

### Window size classes (width)

| Class | Breakpoint | Typical |
|-------|------------|---------|
| Compact | &lt; 600dp | Phones portrait |
| Medium | 600–840dp | Tablets portrait, many fold inner portrait |
| Expanded | 840–1200dp | Tablets landscape, large fold landscape |
| Large / XLarge | 1200+ / 1600+ | Large tablet / desktop windows |

Source: [Use window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).

Use **app window** size, not `isTablet`. Respect **compact height** (phone landscape) so dual-pane isn’t forced.

### Canonical layouts

| Pattern | Role | Editor mapping |
|---------|------|----------------|
| **List-detail** | Collection → item | Gallery optional; **not** tool chrome |
| **Supporting pane** | Primary + secondary tools | **Editor: preview + controls** |
| **Feed** | Adaptive grid | Gallery grid |

Source: [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts). Supporting pane uses cited for **media editing tools**.

### Key APIs

- `currentWindowAdaptiveInfo()` / `WindowSizeClass`
- `SupportingPaneScaffold` + navigator
- `ListDetailPaneScaffold` (if gallery dual-pane)
- `NavigationSuiteScaffold` (bar ↔ rail)
- Nav3: `SupportingPaneSceneStrategy` / `ListDetailSceneStrategy` (adaptive skill prefers SceneStrategy when on Nav3)
- Foldables: `FoldingFeature` (tabletop / book / `isSeparating`)

### Do / don’t (photo editor)

| Do | Don’t |
|----|-------|
| Canvas-first primary pane ~⅔ on expanded | Stretch phone layout full-width on tablet |
| Supporting tools ~⅓, fixed max width + padding | Clip chips against window edge |
| Sheet/rail on compact | Lock orientation / non-resizable |
| Hide nav chrome when consuming full-bleed preview | Put critical controls only on hinge |
| Mouse: hover, right-click, DnD (Desktop already has drop) | Assume touch-only |

X: [@AndroidDev adaptive dos/don’ts](https://x.com/AndroidDev) — use M3 Adaptive; don’t reinvent.

---

## 4. Three schemes (from competitor synthesis)

### A — Supporting Pane Studio

```
[ TopBar ]
[  Preview canvas   | Filmstrip     ]
[  (primary ~70%)   | Tabs/sliders  ]
[                   | (≤360dp)      ]
```

- **Phone:** vertical stack (preview weight + bottom controls)  
- **Tablet/fold open/desktop:** side supporting pane  
- **Interaction:** Always-visible multi-param editing while watching tiles  
- **vs current:** Smallest delta from I1 Expanded; fix chrome quality  

### B — Immersive Canvas + Tool Rail

```
[ Full-bleed preview                |R]
[ Filmstrip peek / hidden chrome    |a]
[ Primary tools → temporary sheet   |i]
```

- **Phone:** stack + peek  
- **Tablet:** vertical FuncType rail  
- **Tabletop fold:** preview top / sheet bottom  
- **Interaction:** Progressive disclosure; hide chrome for edge inspection  
- **vs current:** Best tile inspection; more taps for full Content/Style/Layout  

### C — Three-Zone Batch Studio

```
[ Templates/Images | Canvas | Inspector ]
[                  |        |           ]
[      Filmstrip (optional bottom)      ]
```

- **Phone:** collapse to A/B  
- **Desktop wide:** full three zones  
- **Interaction:** LR Classic / Canva — batch + templates first-class  
- **vs current:** Best multi-image workflow; heaviest build  

**Hybrid framing (recommended):** B density @ Compact · A @ Medium/Expanded · C optional @ ≥1440 desktop.

---

## 5. Form-factor matrix

| Form factor | Primary scheme | Notes |
|-------------|----------------|-------|
| Phone portrait | B / stacked A | Maximize preview height |
| Phone landscape | Single pane | Compact height — no dual-pane |
| Tablet portrait (Medium) | A partial or sheet | Don’t force 360 side if too narrow |
| Tablet landscape (Expanded) | **A** | Align Expanded @ ~840dp |
| Fold cover | Phone | Same as compact |
| Fold inner open | **A** + hinge-aware | Avoid controls on separating hinge |
| Fold tabletop | Preview top / tools bottom | Posture-specific |
| Desktop windowed | A polished | Fix pad/clip |
| Desktop fullscreen / ≥1440 | A or **C** | User-reported fullscreen issues today |

---

## 6. Implementation spine (decision, not commit)

1. **P0 — Fix A quality** (Desktop + Expanded): single preview pad, side pane padding, fixed width rail, no clip.  
2. **P0 — Breakpoint policy:** either lower Expanded to **840**, or give Medium a real half-pane/sheet.  
3. **P1 — Compact B behaviors:** collapsible bottom controls / immersive toggle.  
4. **P1 — Fold (Android):** map `FoldingFeature` → layout flags.  
5. **P2 — SupportingPaneScaffold / Nav3 SceneStrategy** if dual-pane navigation state grows.  
6. **P2 — Scheme C** only with owner demand for batch/templates density.  
7. Screenshot tests: Phone / Foldable / Tablet / Desktop previews (adaptive skill FormFactorPreviews).

---

## 7. Sources

- https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes  
- https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts  
- https://developer.android.com/develop/ui/compose/build-adaptive-apps  
- https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive  
- https://github.com/android/adaptive-apps-samples  
- https://m3.material.io/foundations/layout/canonical-layouts/overview  
- Repo adaptive skill: `.agents/skills/adaptive/SKILL.md`  
- Competitor + official long-form: companion research files above  

---

## 8. Open decisions for owner

1. Accept **hybrid A/B** as product default?  
2. Expand dual-pane at **840** vs keep **1024** with Medium specialization?  
3. Is **Scheme C** in scope for Desktop 1.x or post-CMP parity?  
4. Android-only fold APIs vs shared posture flags for CMP?  
