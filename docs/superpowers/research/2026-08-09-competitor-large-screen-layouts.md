# Competitor large-screen layouts for photo / watermark editors

**Date:** 2026-08-09  
**Scope:** UI region placement (canvas, tools, selection/filmstrip, immersive behavior) across phone, tablet landscape, foldable inner, and desktop/web — synthesized for EasyWatermark (offline privacy watermark app: text/image tile controls + filmstrip + export).  
**Baseline (EasyWatermark today):** `EditorLayoutClass` — Compact `<600`, Medium `600–1023`, Expanded `≥1024`. Compact/Medium = vertical stack (preview weight + filmstrip + bottom controls). Expanded = preview | controls side-by-side with controls pane `widthIn(max = 360.dp)` and ~38% width (`EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP`). See `shared/.../EditorLayoutClass.kt`, `EditorScreen.kt`.

**Evidence grades:** **P** = primary (official help / product blog / vendor site). **S** = secondary reputable writeup. **I** = inference from screenshots, store copy, or community descriptions (called out).

---

## 1. Per-product notes

### 1.1 Google Photos (edit mode)

| Region | Phone | Tablet / large screen | Fold / desktop-ish |
|---|---|---|---|
| **Canvas / preview** | Center, full width above chrome; dark chrome around photo. **P** product editor UI. | Large centered preview; multi-column library outside edit. **P** Android tablet creativity blog. | Split-screen: drag-and-drop of photos to other apps. **P** |
| **Tools / controls** | Bottom: category chips + horizontal tool list + slider/detail for active control; Suggestions tab for one-tap ML edits. **P** 2020 editor launch. | **Side panel** with smart suggestions and tools (explicit large-screen adaptation). **P** Google blog Jul 2023. | Same side-panel pattern when width allows **I**; web gallery is justified grid, not the same edit chrome. **S** |
| **Selection / filmstrip** | Not a multi-photo editor filmstrip; browse is library, edit is single-photo. | Multi-column library; edit is still one photo at a time. | — |
| **Fullscreen / immersive** | Photo-forward; chrome stays for apply/cancel. Recent floating toolbar experiments noted in community. **S** | Side panel leaves more canvas than phone bottom stack. | Split-screen competes with canvas width. |

**Strengths for watermark tiling:** Tablet side panel is the closest first-party pattern to EW Expanded; canvas stays large while controls stay scannable.  
**Weaknesses:** Single-image edit; sparse continuous multi-parameter surfaces (opacity + gap + degree + tile mode at once is denser than Photos’ one-slider-at-a-time model). No template library chrome.

Sources: [New Photos editor](https://blog.google/products-and-platforms/products/photos/new-helpful-editor/) **P**; [Creativity apps on Android tablets](https://blog.google/products-and-platforms/platforms/android/creativity-apps-android-tablets/) **P**; [Edit photos help](https://support.google.com/photos/answer/6128850) **P**.

---

### 1.2 Snapseed (Google)

| Region | Phone | Tablet | Fold / desktop |
|---|---|---|---|
| **Canvas** | Photo is the stage; max visual priority. **S/P** historical product design. | Largely same phone layout scaled **I** (app is mobile-first; no strong official dual-pane story like Photos/Lightroom). | N/A (no desktop product). |
| **Tools** | Bottom: **Looks** vs **Tools** modes; tool grid → tool-specific bottom control panel. Many tools use on-canvas gestures (e.g. vertical drag for intensity). **S** | Same stack **I**. | — |
| **Filmstrip** | None for batch; **Stack** is edit history, not multi-image. | — | — |
| **Immersive** | Tool commit/discard; canvas always primary. Recent UI refresh expanded favorites / workspace customization. **P** store notes. | — | — |

**Strengths:** Best-in-class “preview is sacred” immersion; modal tool focus reduces clutter while adjusting one dimension.  
**Weaknesses for EW:** Watermark config is multi-parameter and live-tiled (not stack-of-one-tool). Modal bottom panels fight simultaneous Content / Style / Layout tabs. No multi-image filmstrip semantics. Weak large-screen differentiation.

Sources: [Snapseed Play listing](https://play.google.com/store/apps/details?id=com.niksoftware.snapseed) **P**; [iPhone Photography School Snapseed guide](https://iphonephotographyschool.com/snapseed/) **S** (Looks/Tools bottom chrome); CNET historical layout note **S**.

---

### 1.3 Adobe Lightroom (mobile) + Lightroom Classic (desktop)

**Mobile / tablet**

| Region | Phone | Tablet |
|---|---|---|
| **Canvas** | Center; long-press before/after. **P/S** | Uses extra space for larger image; precise edits. **P** tablet blog. |
| **Tools** | Horizontal tool categories + right-side / edge adjustments common on phone **S/I**. | **Vertical navigation bar** for editing tools. **P** Google tablet creativity post (Adobe Lightroom called out). |
| **Filmstrip** | Library / album selection outside Develop; in-edit multi-select limited vs Classic. **I** | More room for library grids. |
| **Immersive** | Long-press compare; panels collapse into tool modes. | Same, with rail chrome. |

**Desktop (Classic) — gold standard “studio” chrome**

| Region | Layout |
|---|---|
| **Canvas** | Center content window. |
| **Tools** | Left panel group (presets, history, collections context); right panel group (Develop inspector: Basic, Tone Curve, …). |
| **Filmstrip** | **Bottom, every module** — selection persists across Library/Develop/etc. Hideable. **P** |
| **Immersive** | **Tab** toggles side panels; **Shift-Tab** hides all (sides + filmstrip + module picker); edge hover to peek; F5–F8 panel toggles. **P** |

**Strengths for EW:** Filmstrip as first-class batch selector; dual sidebars for dense params; explicit immersive shortcuts; tablet vertical rail as medium-width compromise.  
**Weaknesses:** Full Classic chrome is heavy for EW’s smaller control surface; dual sidebars underuse space if left is empty. Phone LR is still “pro photo,” not “tile protection.”

Sources: [Lightroom Classic workspace basics](https://helpx.adobe.com/lightroom-classic/desktop/workspace/workspace-basics.html) **P**; [View photos / Filmstrip](https://helpx.adobe.com/lightroom-classic/desktop/viewing-photos/view-photos.html) **P**; [Creativity apps tablets — Lightroom](https://blog.google/products-and-platforms/platforms/android/creativity-apps-android-tablets/) **P**.

---

### 1.4 PicsArt

| Region | Phone | Tablet / web |
|---|---|---|
| **Canvas** | Center image; layers/stickers as overlays. | Responsive canvas; drag-and-drop design tools emphasize flexible layout. **P** |
| **Tools** | **Bottom toolbar** (Tools, effects, etc.) → subtools. **P** blog how-tos. | Workspace “adapts”; design flows closer to Canva-like panels on larger surfaces **I**. |
| **Filmstrip** | Project/layers more than multi-export filmstrip. | — |
| **Immersive** | Tool modes expand bottom sheets over canvas. | Cross-device sync (cloud). |

**Strengths:** Familiar phone bottom-tool taxonomy; sticker/layer placement metaphors map partly to single-logo watermarks.  
**Weaknesses for EW:** Heavy discovery/monetization chrome; tiling/repeat protection not the product center; cloud identity clashes with EW privacy positioning.

Sources: [PicsArt photo editing basics](https://picsart.com/blog/photo-editing-basics/) **P**; [Drag-and-drop tools](https://picsart.com/ease-of-use/drag-and-drop-design-tools/) **P**.

---

### 1.5 Canva

| Region | Phone | Tablet | Desktop / web |
|---|---|---|---|
| **Canvas** | Center design page. | Larger canvas with vertical nav. **P** tablet blog. | Center stage; multi-page designs. **P** |
| **Tools** | Bottom panel ≈ desktop left object panel (Design, Elements, Text, Uploads, Tools…). **S** tutorials. | **Vertical navigation bar** on large screens for more work space. **P** | Left **side panel** (tabs pin open / hover); **edit panel** after selection; floating toolbar on selected element; top app bar. **P** help (Tools tab, glow-up editor). |
| **Filmstrip** | Pages strip / designs list more than photo filmstrip. | Same. | Page thumbnails / project nav. |
| **Immersive** | Sheets overlay; less “hide all chrome” than LR. | Nav rail collapses content browser. | Side panel close / pin; still design-chrome-heavy. |

**Strengths:** Clearest mapping of **content browser (left) ↔ canvas (center) ↔ properties (contextual)**; templates as first-class left-rail content (relevant to EW templates). Vertical rail on large screens matches Material large-screen guidance.  
**Weaknesses:** Infinite design surface, not “protect this photo set”; many panels irrelevant offline; export is design download not privacy-stripped photo batch.

Sources: [Canva Tools tab](https://www.canva.com/help/tools-tab/) **P**; [Glow-up editor / side panel](https://www.canva.com/help/glow-up-variantb/) **P**; [Creativity apps tablets — Canva](https://blog.google/products-and-platforms/platforms/android/creativity-apps-android-tablets/) **P**.

---

### 1.6 Watermark-specific apps

#### eZy Watermark Photos

- **Phone-first stack:** pick single vs batch → preview → customize opacity, rotation, size, position; **tile** and **snap to grid** called out. **P** store.  
- **Batch preview:** grid vs slide toggle (recent UI). **P** App Store release notes.  
- **Layout I:** classic mobile editor (canvas + bottom/side property sheets); batch is a separate preview browser, not always an always-on filmstrip during live tile edit.  
- **Strengths:** Feature set closest to EW (tile protection, batch).  
- **Weaknesses:** Dense menus; large-screen / fold stories not marketed as dual-pane studio layouts.

Sources: [Play](https://play.google.com/store/apps/details?id=com.whizpool.ezywatermarklite) **P**; [App Store](https://apps.apple.com/us/app/ezy-watermark-photos/id494473910) **P**.

#### iWatermark+

- **Phone:** watermark-type library + fine-tune (font, size, angle, opacity) with live preview; templates reusable. **P**  
- **iPad:** “extra breathing room”; wider canvas for type selection + preview scale; template composition. **P** vendor copy + screenshots descriptions.  
- **Strengths:** Template library + multi-type marks; iPad canvas prioritization.  
- **Weaknesses:** More mark types (QR, stego, metadata) → heavier chrome than EW’s text/image tile focus; tiling not the sole hero.

Sources: [iWatermark+ product page](https://plumamazing.com/iwatermark-plus-android) **P**.

#### PhotoMarks / similar

- Limited primary large-screen layout docs found in this pass; category pattern **I** remains: phone bottom property sheets + export, optional batch queue, logo placement gestures. Treat as corroboration of eZy/iWatermark, not independent layout research.

---

### 1.7 Apple Photos + Markup (iPhone / iPad)

**Photos Edit**

| Region | iPhone | iPad |
|---|---|---|
| **Canvas** | Full center; dark frame. | Larger center stage. |
| **Tools** | Bottom categories: Adjust / Filters / Crop; sliders under active tool. **P** | Filters/tools appear on the **right side** of the screen in edit guidance. **P** iPad User Guide wording. |
| **Filmstrip** | Not multi-edit filmstrip. | Same single-asset edit. |
| **Immersive** | Cancel / Done; Auto enhance; hold to compare (common Photos pattern) **I**. | Side tools free vertical canvas vs phone bottom stack. |

**Markup**

- Canvas with drawing tools; **toolbar** can be **dragged to any edge**; **Auto-minimize** while drawing/text; hide via Done. **P** Support / iPad guide.  
- Known issue: top Done/Cancel and bottom tools can obscure photo edges (community) — relevant to EW chrome collision with edge tiles.

Sources: [Markup on iPhone/iPad](https://support.apple.com/en-us/119875) **P**; [iPad Markup in apps](https://support.apple.com/guide/ipad/add-text-shapes-stickers-and-more-ipad8869ac3a/ipados) **P**; [Edit photos on iPad](https://support.apple.com/guide/ipad/edit-photos-and-videos-ipad735956e8/ipados) **P**.

---

### 1.8 Large-screen / foldable patterns (platform)

Android / Material large-screen guidance and Google’s large-screen gallery emphasize **canonical layouts**: list-detail, supporting pane, feed — responsive across phone → fold → tablet → ChromeOS. **S/P** coverage of gallery + foldables docs.

Creativity apps that “did it right” (official callouts):

- **Google Photos** — multi-column library + **edit side panel**.  
- **Lightroom** — **vertical tool nav** + large canvas.  
- **Canva** — **vertical navigation bar** + drag-drop in multi-window.

Foldables **I** for photo editors:

- **Inner landscape ≈ tablet:** supporting pane or dual pane.  
- **Tabletop posture:** canvas upper / controls lower is a natural map for phone-stack chrome (controls already bottom).  
- **Outer cover:** Compact phone stack only.

Sources: [Creativity apps Android tablets](https://blog.google/products-and-platforms/platforms/android/creativity-apps-android-tablets/) **P**; [Learn about foldables](https://developer.android.com/develop/adaptive-apps/guides/foldables/learn-about-foldables) **P**; large-screen gallery coverage **S**.

---

## 2. Cross-cutting patterns (synthesis)

| Pattern | Who uses it | Best for EW? |
|---|---|---|
| **A. Bottom stack (phone)** | Snapseed, Photos phone, PicsArt, EW Compact | Default phone / cover screen |
| **B. Supporting pane (tablet)** | Photos tablet edit, Apple Photos iPad tools right, EW Expanded, LR tablet rail variant | Live tile preview + dense sliders |
| **C. Dual sidebar + filmstrip (desktop studio)** | Lightroom Classic, Canva left+canvas(+props) | Desktop / ultra-wide; templates + batch |
| **D. Modal tool immersion** | Snapseed tools, Markup auto-minimize | Single-param focus; weaker for multi-tab tile config |
| **E. Vertical nav rail** | LR tablet, Canva large, Material adaptive | Medium width (fold open, small tablet) without full 360dp pane |
| **F. Persistent filmstrip** | LR Classic bottom; EW strip; eZy batch preview | Multi-image watermark export workflows |
| **G. Immersive chrome hide** | LR Tab/Shift-Tab; Markup minimize | Judging tile density / edge coverage |

**Implication for watermark tiling specifically:** the user must **see the full tile field** (including edges and diagonal AABB) while **adjusting multiple continuous parameters**. That favors **large stable canvas + always-visible multi-control surface** over Snapseed-style one-tool modals. Batch export favors a **persistent filmstrip** (LR/EW), not library-exit-and-return.

---

## 3. EasyWatermark baseline (for comparison)

```
Compact / Medium (width < 1024):
┌────────────────────────────┐
│ TopBar  back · add · save  │
├────────────────────────────┤
│                            │
│     Preview (weight 1)     │
│                            │
├────────────────────────────┤
│ Filmstrip (if images)      │
├────────────────────────────┤
│ Bottom controls / tabs     │
│ Content · Style · Layout…  │
└────────────────────────────┘

Expanded (width ≥ 1024):
┌──────────────────────────────────────────┐
│ TopBar                                   │
├────────────────────────┬─────────────────┤
│                        │ Filmstrip       │
│   Preview (weight 1)   │ Controls ≤360dp │
│                        │ (~38% width)    │
│                        │ tabs + sliders  │
└────────────────────────┴─────────────────┘
```

**Known tension:** capping the supporting pane at 360dp is Photos/Apple-like and keeps canvas large, but on 1440–1920dp desktops the empty horizontal margin around a portrait photo can feel sparse while the pane still scrolls dense options; filmstrip-in-pane competes with controls height; Medium (e.g. 800dp tablet portrait / fold) still uses phone stack and may underuse width.

---

## 4. Scheme A / B / C (proposal for EasyWatermark)

### Scheme A — “Supporting Pane Studio” (current Expanded refined)

**Name:** Supporting Pane Studio  
**Spirit:** Google Photos tablet edit + Apple Photos iPad right tools + current EW Expanded.

**Wireframe (Expanded / tablet landscape / desktop default):**

```
┌─ TopBar: back | add | templates | about | export ──────────────┐
├──────────────────────────────────────┬─────────────────────────┤
│                                      │ [filmstrip horizontal]  │
│                                      ├─────────────────────────┤
│         PREVIEW / TILE CANVAS        │ Tab: Content|Style|     │
│         (max remaining width)        │      Layout|…           │
│                                      │ sliders / text / icon   │
│                                      │ (pane max 320–400dp)    │
│                                      │ sticky Export CTA       │
└──────────────────────────────────────┴─────────────────────────┘
```

**Optional Medium (600–1023):** same side pane but narrower (280dp) **or** collapsible rail that expands to pane on tool select.

**Form-factor matrix**

| FF | Behavior |
|---|---|
| **Phone** | Keep Compact vertical stack. |
| **Tablet landscape** | Supporting pane right (or left for LTR preference later). |
| **Foldable inner** | Treat as tablet; if hinge book-posture, put preview on one half / controls on other (hinge-aware split **I** enhancement). |
| **Desktop** | Same as Expanded; optional keyboard: `]` toggle pane, `F` focus fullscreen preview. |

**Interaction model**

- Live config always visible while watching tiles.  
- Filmstrip in pane top (current) **or** under preview full-width if image count > N (variant).  
- Sheets (template, text edit, export) remain modal overlays.  
- Long-press / hold preview = temporary chrome dim for edge inspection (LR compare analogue).

**Pros vs current Expanded≤360dp**

- Minimal migration; already shipped structure.  
- Matches platform-endorsed tablet photo edit pattern.  
- Canvas stays large for tile judgment.

**Cons**

- Filmstrip + tabs fight for vertical space in a short pane.  
- Medium widths still awkward if not given a rail/pane.  
- Desktop underuses ultra-wide without optional third column (templates).

---

### Scheme B — “Immersive Canvas + Rail” (Snapseed/Photos phone × LR/Canva rail)

**Name:** Immersive Canvas + Tool Rail  
**Spirit:** Snapseed’s canvas primacy + Lightroom/Canva vertical nav + Markup auto-minimize.

**Wireframe (phone / immersive default):**

```
┌─ thin TopBar (auto-hide on scroll / after idle) ─┐
│                                                   │
│              PREVIEW FULL BLEED                   │
│         (controls as bottom sheet peek)           │
│                                                   │
├─ filmstrip thin ──────────────────────────────────┤
├─ peek: opacity · gap · degree  |  ⌃ expand sheet ─┤
└───────────────────────────────────────────────────┘

Expanded sheet / tool focus:
┌───────────────────────────────────────────────────┐
│ PREVIEW (shrunken weight)                         │
├───────────────────────────────────────────────────┤
│ full controls sheet (tabs)                        │
└───────────────────────────────────────────────────┘
```

**Wireframe (Medium+ with rail):**

```
┌──┬──────────────────────────────────────────────┐
│T │  PREVIEW                                     │
│e │                                              │
│x │                                              │
│t │                                              │
│⚙ │                                              │
│▦ │                                              │
│  ├──────────────────────────────────────────────┤
│  │ filmstrip                                    │
└──┴──────────────────────────────────────────────┘
  tap rail icon → temporary side sheet (not permanent 360 pane)
```

**Form-factor matrix**

| FF | Behavior |
|---|---|
| **Phone** | Full-bleed preview; bottom peek bar for primary three sliders (opacity, size/gap, rotation); expand for full tabs. |
| **Tablet** | 72–80dp vertical rail of FuncTypes; detail sheet overlays or docks only while editing a group. |
| **Foldable** | Cover = phone; open = rail; tabletop = preview top / sheet bottom (natural). |
| **Desktop** | Rail + optional docked inspector; default more chrome-hidden than Scheme A. |

**Interaction model**

- **Progressive disclosure:** primary tile params always one gesture away; secondary (typeface, paint style, color) behind rail icons / expanded sheet.  
- Double-tap canvas or shortcut hides all chrome (Markup/LR immersive).  
- Filmstrip is edge-thin and hideable so multi-select doesn’t steal tile view.

**Pros vs current Expanded≤360dp**

- Best tile-edge inspection; less permanent chrome.  
- Strong phone + fold tabletop story.  
- Medium widths get a rail without committing 360dp forever.

**Cons**

- Multi-parameter workflows cost more taps (anti-Snapseed for power users who want all sliders).  
- Larger implementation delta from current Expanded row.  
- Risk of “hidden export” if TopBar auto-hides poorly.

---

### Scheme C — “Three-Zone Batch Studio” (Lightroom Classic × Canva)

**Name:** Three-Zone Batch Studio  
**Spirit:** LR Classic module chrome + Canva left browser + EW templates/filmstrip.

**Wireframe (desktop / large tablet landscape):**

```
┌─ TopBar: project · export · about ─────────────────────────────┐
├────────────┬───────────────────────────────┬───────────────────┤
│ TEMPLATES  │                               │ INSPECTOR         │
│ · saved    │     PREVIEW / TILE CANVAS     │ Content           │
│ · defaults │                               │ Style             │
│            │                               │ Layout / tile     │
│ IMAGES     │                               │ Output prefs      │
│ (vert.     │                               │                   │
│  strip or  │                               │ [Apply to all]    │
│  list)     │                               │                   │
├────────────┴───────────────────────────────┴───────────────────┤
│ FILMSTRIP (horizontal, hideable) · selection · add more        │
└────────────────────────────────────────────────────────────────┘
Immersive: hide left+right (Tab); hide all including filmstrip (Shift-Tab analogue)
```

**Form-factor matrix**

| FF | Behavior |
|---|---|
| **Phone** | Collapse to Scheme A Compact or B stack; left zone becomes template sheet; inspector = bottom tabs. |
| **Tablet** | Optional two-zone (hide left): preview + inspector (= Scheme A). Show left only when templates/images browser open. |
| **Foldable** | Book: left browser | preview, inspector as sheet; or preview | inspector with templates in sheet. |
| **Desktop** | Full three-zone default; remember pane visibility. |

**Interaction model**

- **Session batch:** filmstrip selection + “apply watermark config to all” explicit (EW already session-scoped config — surface it).  
- Templates as browsable left content (Canva), not only a modal sheet.  
- Keyboard-first desktop: arrow keys filmstrip, Tab immersive, Enter export sheet.  
- Output prefs can live in inspector instead of only save sheet (optional).

**Pros vs current Expanded≤360dp**

- Best match for multi-image export + template-heavy users.  
- Desktop no longer feels like a stretched phone with a 360dp column.  
- Clear place for future “apply to selected” without crowding preview.

**Cons**

- Heaviest build; left column empty state must not look barren.  
- More chrome → higher risk of obscuring tile edges unless immersive is excellent.  
- Overkill if most users watermark 1–3 photos on phone.

---

## 5. Recommendation framing (not a product decision)

| Priority | Lean toward |
|---|---|
| Ship continuity / parity with current I1 Expanded | **Scheme A** (+ Medium rail polish) |
| Tile visibility & fold tabletop | **Scheme B** |
| Desktop / power batch + templates | **Scheme C** (desktop-first, adaptive collapse) |

**Hybrid many products already imply:**  
**B on Compact**, **A on Medium/Expanded tablet**, **C on desktop ultra-wide** — one information architecture with three density modes, not three apps.

Suggested adaptive mapping (for a future ADR, not binding here):

| `EditorLayoutClass` | Scheme density |
|---|---|
| Compact | B stack (or keep today’s A stack) |
| Medium | B rail **or** A narrow pane |
| Expanded | A supporting pane |
| Expanded + width ≥ 1440 (new breakpoint?) | C three-zone |

---

## 6. Open questions for design / owner

1. Should Medium (fold open, 7–8" tablet portrait) leave the phone stack, or gain a rail/pane before 1024?  
2. Filmstrip placement: always with controls (today Expanded), always under canvas, or adaptive by count?  
3. Is template browsing frequent enough to deserve a permanent left zone (C) vs modal sheet (today)?  
4. Immersive hold-to-preview: hide chrome only, or also temporarily boost opacity for edge check?  
5. Desktop keyboard map and whether JVM desktop should default denser than Android Expanded tablet.

---

## 7. Source index

| # | Source | Grade |
|---|---|---|
| 1 | Google Photos new editor blog (2020) | P |
| 2 | Google: 6 creativity apps for Android tablets (Photos side panel, LR vertical nav, Canva vertical nav) | P |
| 3 | Google Photos edit help | P |
| 4 | Snapseed store listings | P |
| 5 | iPhone Photography School Snapseed guide (Looks/Tools) | S |
| 6 | Adobe Lightroom Classic workspace + filmstrip help | P |
| 7 | Canva Help: Tools tab; glow-up side/edit panels | P |
| 8 | PicsArt blog / drag-and-drop | P |
| 9 | eZy Watermark Play + App Store | P |
| 10 | iWatermark+ Plum Amazing product page | P |
| 11 | Apple Support Markup; iPad Photos edit / Markup guides | P |
| 12 | Android foldables adaptive docs | P |
| 13 | EW `EditorLayoutClass.kt` / `EditorScreen.kt` | code |

**Inference disclaimer:** Phone-vs-tablet dual-pane details for Snapseed, PicsArt, and some watermark apps are partly extrapolated from mobile-first UIs and marketing screenshots where vendors did not publish large-screen layout specs. Platform-official callouts (Photos, Lightroom, Canva on tablets) and desktop Lightroom/Canva docs are treated as strongest anchors.
