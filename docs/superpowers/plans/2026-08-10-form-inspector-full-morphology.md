# Plan — Form Inspector full DEMO morphology

**Status:** implementation claimed at `ad8193b9` · ACSP `20260810-103300--form-inspector-full-morph` in **review** · coordinator Accept + device residual open  
**Branch:** `feat/migrate_to_compose`  
**Binding visual:** `docs/superpowers/research/easywatermark-inspector-panel-redesign-demo.html`  
**IA locked:** `docs/adr/0026-adaptive-editor-layout-ia.md` (840 dual-pane / 1440 three-zone; inspector **interior** only)

---

## Goal (one breath)

Make Expanded/Wide supporting inspector a **DEMO-complete form** (principles **01–06**), not a phone bottom bar stood on end and not a shell-only top-tab wrapper. Phone Compact/Medium chrome stays. Prove Desktop hard doors with screenshots; Android/iOS regression with reused devices.

---

## Why

| Layer | What shipped | Owner reaction |
|---|---|---|
| ADR-0026 | dual-pane@840, three-zone@1440 | accepted |
| Shell AE58 (`ae58b98e`) | top tabs + scroll + no Center void | partial — “样式怎么没更新” |
| Full morph (`ad8193b9`) | segment / conditional / inline text / labeled sliders | **in review** — Accept only if hard doors pass visual |

Shell-only Accept is **forbidden**. DoD is DEMO morphology, not “top Tab exists.”

---

## DEMO principles → acceptance mapping

| # | Principle | Hard? | Evidence required |
|---|---|---|---|
| 01 | 侧栏 ≠ 竖着的底栏 · Expanded/Wide use Form Inspector, not Center frame | shell | Desktop Expanded/Wide screenshots; code path dualOrWide → `EditorInspectorPanel` |
| 02 | 顶对齐 + 可滚动 · no vertical center void | shell | Same shots; no large empty mid-rail |
| 03 | 标签/值同行 · slider **left label + right mono value**; field label above inputs | **HARD** | Style + Layout Desktop PNGs |
| 04 | Segment 替代大卡片 · equal-width Text\|Icon; **only active-mode fields** below | **HARD** | Content Text + Content Icon PNGs |
| 05 | Tab 吸顶 · Content/Style/Layout at top | shell | All Expanded shots |
| 06 | IA 不变 · same three tabs + FuncType surface; presentation rhythm only | soft | no FuncType catalog cut; phone carousel intact |

**Extra hard (owner):** side-pane **inline text edit** (Outlined field + template affordance), not summary-row → sheet only.

---

## Ground truth (2026-08-10)

### Verified

- DEMO HTML + tokens (320–360dp rail, 12–14 pad/gap, section 16–18, control 36, tab 40–44).
- ADR-0026 breakpoints frozen; no Scaffold / Nav3 SceneStrategy / FoldingFeature this period.
- Shell commit `ae58b98e`; morph commit `ad8193b9` (local; branch ahead of origin by ≥1).
- Desktop E2E props: `-PewmAutoOpen`, `-PewmW`, `-PewmH`, `-PEwmInspectorTab`, `-PEwmForceMarkMode`.
- Worker artifacts under ACSP session `artifacts/desktop/`:
  - `expanded-content.png` / `expanded-content-icon.png`
  - `expanded-style.png` / `expanded-layout.png`
  - `wide-content.png` / `wide-content-icon.png`
- Unit: `EditorInspectorFormFields` + `*Inspector*` / `*EditorLayoutClass*` claimed green.
- Compile: desktop + app debug + iOS sim Kotlin claimed green.
- **Android/iOS device smoke for morph: SKIP at worker** (empty adb / no booted sim) — residual for coordinator or residual session.

### Safety standing order

- **Never** global `CGEvent` / `click at` / system keystrokes to arbitrary apps (prior logout risk).
- Desktop image import: **only** `-PewmAutoOpen` (no FileDialog osascript).
- Window resize: System Events on **MainKt/java** EasyWatermark window only.
- Capture: `screencapture -l <CGWindowID>` after Screen Recording granted to the agent process.
- Do not shut down owner emulators/simulators unless ordered.
- Local commit OK; **git push only on owner order**.

---

## Task checklist

### A. Morphology (implementation)

- [x] A1 Extract form field helpers (`EditorInspectorFormFields`) + unit tests on real catalog types
- [x] A2 Content: equal-width Text|Icon segment (`DesignChoiceChips.equalWidth`)
- [x] A3 Content: only active-mode fields under segment (no permanent Text+Icon stack)
- [x] A4 Content: form-path **inline** text (`TextContentOption.inlineEdit`) + template affordance
- [x] A5 Form sliders: left visible label + right mono value (`SliderOption.showLabel`); phone path default unchanged
- [x] A6 Style/Layout form rhythm (sections TILE/LOOK/GAPS or 平铺/外观/间距); top tabs retained
- [x] A7 Mode switch via `WatermarkConfigChange.MarkMode` (no payload rewrite)
- [x] A8 Local commit `ad8193b9`; no push

### B. Desktop proof (primary Accept gate)

- [x] B1 Expanded Content Text — segment + inline field
- [x] B2 Expanded Content Icon — icon pick only
- [x] B3 Expanded Style — labeled sliders
- [x] B4 Expanded Layout — gap labeled sliders
- [x] B5 Wide three-zone + form Content
- [x] B6 Coordinator **visual Accept** of hard doors 1–4 (not worker prose alone)
- [x] B7 Re-run unit tests → `scratch/form-inspector-unit.log` (or ACSP verification recheck)

### C. Android / iOS residual

- [x] C1 Reuse/boot devices; **do not** kill owner sims
- [x] C2 Android phone (<840): bottom chrome 内容/样式/布局 preserved
- [x] C3 Android tablet/≥840 (or 960 window): form inspector top tabs + hard doors visible
- [x] C4 iOS compile + best-effort phone/pad sim smoke
- [x] C5 Copy evidence → `docs/superpowers/research/parity-shots/2026-08-10-form-inspector/` (or morph subfolder) + ACSP artifacts

### D. Closeout

- [x] D1 Coordinator `review.md` with verdict Accept | Revise
- [x] D2 ACSP transition `review → done` (or revise → inbox)
- [x] D3 Flip this plan checkboxes; note residual deferrals if any
- [ ] D4 Optional polish: Chinese section headers 平铺/外观/间距 (non-blocking unless owner insists)

---

## Definition of Done (product)

1. **Hard doors (blocking Accept):**
   1. Content equal-width **文字 | 贴纸** (or Text|Icon) segment on Expanded/Wide
   2. **Only active-mode fields** under segment
   3. **Inline text** field in side pane (not summary-only)
   4. Form sliders **left label + right mono value** on Style/Layout
2. Shell 01/02/05 retained (top tabs, top-aligned scroll, no Center void).
3. Compact/Medium phone bottom chrome **unchanged**.
4. No parallel control system — reuse chips/slider/theme; form wrappers only.
5. Unit + compile green with evidence logs.
6. Desktop screenshots prove hard doors; Android/iOS residual completed or explicitly deferred with owner OK.
7. ACSP Accept by coordinator after live visual review; local commit; no push unless ordered.

---

## Out of scope

- Changing ADR-0026 840/1440 breakpoints
- SupportingPaneScaffold / Nav3 SceneStrategy / FoldingFeature
- Template left rail redesign
- FileDialog / global input automation
- git push / worktrees / Weblate non-default locales
- Re-Accept of shell-only AE58 as “full morph done”

---

## Process (owner flow)

```
Frame complete goal (this plan + ACSP task.md)
  → publish ACSP → Herdr worker implements
  → Desktop E2E screenshots (hard doors)
  → coordinator visual review
  → Accept | Revise (revise if shell-only or doors fail)
  → Android/iOS residual (reuse devices)
  → plan checklist flip → done
```

**Roles:** Grok = coordinator/reviewer only (does not implement worker mission in coordinator pane). Worker = implementation + first-pass evidence.

**Current session:** `~/.agent-cowork/sessions/easywatermark/review/20260810-103300--form-inspector-full-morph`

---

## Verification commands

```bash
# Unit (morph helpers + layout class)
./gradlew :shared:desktopTest \
  --tests '*EditorLayoutClass*' --tests '*Inspector*' \
  --max-workers=8

# Compile three hosts
./gradlew :desktopApp:compileKotlin \
  :app:compileDebugKotlin \
  :shared:compileKotlinIosSimulatorArm64 \
  --max-workers=8

# Desktop E2E samples (paths local)
./gradlew :desktopApp:run \
  -PewmAutoOpen=<sample1.png,sample2.png> \
  -PewmW=960 -PewmH=720 \
  -PEwmInspectorTab=0 \
  -PEwmForceMarkMode=text \
  --max-workers=4
# then Style tab=1, Layout tab=2; Wide -PewmW=1470 -PewmH=900
# capture: screencapture -l <CGWindowID>
```

---

## Status matrix (live)

| Item | State |
|---|---|
| Shell AE58 | done / accepted earlier |
| Full morph code `ad8193b9` | claimed complete |
| Desktop hard-door shots | **Accept PASS** |
| Unit/compile recheck | PASS |
| Android phone + ≥840 | PASS residual |
| iOS compile | PASS residual |
| ACSP `review → done` | **done** |
| Push | not requested |

---

## Notes / polish backlog (non-blocking)

- Section headers currently MODE/TILE/LOOK/GAPS vs DEMO 平铺/外观/间距 — optional string polish.
- After Icon E2E, persisted prefs may stay Image mode — force prop pins shots only.
- Layout gap values depend on stored prefs; not a chrome defect by itself.
