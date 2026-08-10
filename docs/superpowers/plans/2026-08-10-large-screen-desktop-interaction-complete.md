# Plan / Goal — Large-screen & Desktop interaction complete

**Status:** Implemented (worker 2026-08-10); awaiting coordinator Accept · owner 2026-08-10 “所有都要做”  
**Branch:** `feat/migrate_to_compose`  
**Research binding:** `docs/superpowers/research/2026-08-10-large-screen-desktop-interaction-debt.html`  
**Layout binding:** `docs/adr/0026-adaptive-editor-layout-ia.md` (amended: dual-pane ≥840, no three-zone left rail)  
**Form binding:** inspector DEMO + shipped morph (`ad8193b9` lineage)

---

## Goal kind

code-change (multi-slice product UX; one umbrella goal, ordered batches)

---

## Mission (one breath)

Close **all** large-screen / Desktop **interaction debt** so Expanded/Wide/Desktop is not a “phone UI stood on end”: **no capability regression vs phone**, pointer+keyboard first-class, sheets have large-screen variants, About adapts, export adapts, with contract tests + Desktop evidence. Local commits; **no push** unless ordered.

---

## Why This Matters

| Layer | State | Gap |
|---|---|---|
| ADR-0026 | dual-pane ≥840 | “where panes go” only |
| Form Inspector morph | segment / labeled sliders / inline text shell | **inline text `singleLine=true`** → no newline / no 2nd line |
| Preview / filmstrip | Fit buckets + resize recenter | residual polish (keys, draft bucket) |
| About / Export / Desktop host | phone scroll + bottom sheet + FileDialog | not desktop-native |

Owner: **全部做** — not a partial P0-only slice. Principle from research: **大屏路径不得功能倒退**.

---

## Ground Truth

### Verified

- Research report inventories **12+** items across A–F classes (input fidelity, space form, pointer/keyboard, viewport, density, path contracts).
- `TextContentOption.InlineTextContentField`: **`singleLine = true`** while phone `WatermarkTextEditSheet` allows multiline; raster `MultiParagraph` already paints `\n`.
- About: `AboutScreenShell` single-column + HorizontalPager dev cards; **no** `layoutClass` branch.
- Export: `SaveExportSheetShell` ModalBottomSheet phone drawer semantics on all widths.
- Desktop: AWT `FileDialog`; safe E2E `-PewmAutoOpen` / `-PewmW` / `-PewmH`; no global click automation (standing safety).
- Wide left session library **removed** (owner); filmstrip is sole multi-image switcher on dual-pane.

### Binding decisions (do not re-litigate without block)

- Breakpoints **840 dual-pane**; no SupportingPaneScaffold / Nav3 SceneStrategy / FoldingFeature this goal.
- No reintroducing three-zone left rail.
- Phone Compact/Medium bottom chrome stays default path.
- ACSP: coordinator Accept vs hard doors; worker implements; local commit; no push unless ordered.
- Desktop automation: **only** `-PewmAutoOpen` + window-local resize; never global CGEvent/keystroke to arbitrary apps.

### Inference (labeled)

- “All” = full research matrix P0+P1+P2 in this umbrella goal, ordered in batches A→F; may land as multiple local commits / one or more ACSP sessions, but **one DoD** — not done until matrix complete or owner explicitly defers a P2 with name.

---

## Operating Stance

- **Tempo:** finish-oriented batch delivery against locked research matrix.
- **Quality bar:** large-screen path capability ≥ phone path; Desktop feels like a workstation tool, not a stretched phone.
- **Risk posture:** no layout-IA re-open; no parallel control system; safe Desktop E2E only; preserve session/export/privacy offline promises.

---

## Hard doors (Accept blocked without proof)

| # | Door | Proof |
|---|---|---|
| H1 | Form inline watermark text is **multiline** (Enter newline; ≥2–3 visible lines; no singleLine trap) | Desktop Content form PNG + unit/contract |
| H2 | Raster shows multiline cell when text has `\n` | Preview shot or golden/unit on shipped composer path |
| H3 | Phone text sheet / bottom summary **no regression** | Phone shot or code-path default + test |
| H4 | Export ≥840 has **non-phone-drawer** variant (centered dialog and/or dual-pane options\|list) | Desktop/Android ≥840 shot |
| H5 | About ≥840: **content max-width** + **dev/designer side-by-side** (no forced pager-only) | Desktop About shot |
| H6 | Desktop **open/save shortcuts and/or drop** equivalent to FileDialog for main import path | Notes + manual/E2E (autoOpen remains for CI-safe import) |
| H7 | Sliders: **keyboard and/or scroll-wheel** step without only drag | Desktop note + unit if pure helper |
| H8 | Path contract tests: formPath cannot silently re-disable multiline / labeled sliders | Unit green log |

Shell retained from prior goals: dual-pane form inspector, filmstrip center frame on resize, section i18n.

---

## Execution Spine (batches — all required)

### Batch A — Input fidelity (P0)

1. Form inline text: remove `singleLine=true`; `minLines` 3–5, `maxLines` ~8; soft wrap; live `\n` to config.
2. Shared defaults helper for text fields (form + sheet) so paths cannot diverge.
3. Contract unit tests on shipped flags/helpers.
4. Optional: bottom summary Medium+ shows 2 lines or “N lines” affordance (P1 can absorb if tight).

### Batch B — Sheets → large-screen variants (P1)

5. Save/export: ≥840 centered dialog or options\|file-list dual pane; Compact keeps bottom sheet.
6. Template list/edit: Expanded dialog max-width or inspector-adjacent presentation; no capability cut.

### Batch C — About adaptive (P1)

7. About ≥840: limit content measure (~720–840dp center or supporting layout); Dev+Designer **two cards side-by-side** (pager optional Compact only).
8. Optional list\|detail for long rows (version/oss/privacy); keep brand halo.
9. Desktop: store rating/feedback → open URL or copy; no fake Play-only dead ends without label.

### Batch D — Desktop host (P1)

10. Keyboard: open image, save/export primary, (optional) save-as — platform menu or Window shortcuts.
11. Drag-drop already for images; extend **icon drop** where icon pick exists; recent-dir remember if cheap.
12. Keep `-PewmAutoOpen` for agent-safe E2E (not a substitute for user shortcuts).

### Batch E — Pointer density (P1–P2)

13. Slider: arrow keys ±1, Shift±10; scroll wheel when focused; optional double-click reset.
14. Filmstrip: ←/→ when focused (P2).
15. Focus rings / Tab order pass on Desktop editor + About (P2).

### Batch F — Viewport polish (P2)

16. Draft preview bucket: consider Desktop draft ≥1080 (document tradeoff).
17. Open-source / long text: max-width ~65ch; optional TOC ≥840.
18. HEX color: paste normalize + error (keep singleLine).

### Closeout

19. Desktop screenshots: multiline Content, Export large, About large, shortcut/drop note.
20. Android phone + ≥840 residual; iOS compile (+ sim best-effort).
21. `result.md` / `verification.md`; hard-door checklist; local commit(s); ACSP Accept; **no push**.

---

## Boundaries

**In scope:** All research matrix items (P0–P2); shared CMP UI; Desktop host; About/OpenSource; SaveExport; TextContent/Slider/filmstrip; tests; local commits; dual-write EN strings if new keys (locales as needed for user-facing).

**Out of scope:** Re-opening ADR breakpoints; three-zone left rail; SupportingPaneScaffold/Nav3 Scene; FoldingFeature; Weblate bulk unless new keys; git push; worktrees; global input automation; R8/Play listing work.

**Requires confirmation:** git push; ADR breakpoint change; killing owner emulators.

---

## Definition of Done

- [x] **H1–H8** hard doors proven (screenshots + tests as specified)
- [x] Research matrix rows all **Done** or **owner-named defer** (default: none deferred)
- [x] Compact/Medium phone chrome no intentional regression
- [x] Unit + `:desktopApp:compileKotlin` + `:app:compileDebugKotlin` + iOS sim Kotlin green
- [x] Desktop evidence under ACSP artifacts and/or `docs/superpowers/research/parity-shots/`
- [x] Android ≥840 + phone best-effort; iOS compile
- [x] ACSP Accept (or multi-session Accept chain) + local commit(s); no push unless ordered

**Explicitly not done:** only multiline fix; only About; “shortcuts later”; Accept without H4/H5.

---

## Acceptance criteria (harness-style)

1. Form-path watermark text supports **multiline edit and display** (Enter inserts newline; field shows ≥2 lines; config receives `\n`); phone sheet path remains multiline-capable; bottom chrome not broken.
2. Export UI at width **≥840dp** uses a large-screen variant (not only phone bottom-sheet chrome).
3. About at **≥840dp** uses limited content width and side-by-side developer/designer cards (pager not the only Expanded presentation).
4. Desktop provides keyboard and/or drop equivalents for primary open (and save/export entry) without relying solely on clicking FileDialog menus; agent E2E may still use `-PewmAutoOpen`.
5. Form sliders support non-drag adjustment (keyboard and/or wheel) on Desktop/form path.
6. Contract tests prevent formPath regressions on multiline (and retain labeled slider / segment morph).
7. P2 items (filmstrip keys, focus, OSS measure, draft bucket, HEX paste) implemented unless owner lists a named defer in result.md.
8. Verification logs + Desktop (and best-effort Android/iOS) evidence; local commit; no push unless ordered.

---

## Verification plan

1. **gating:** Unit tests for text field multiline contract, any pure slider-step helpers, layout/about width helpers if extracted. Log → `{SCRATCH}/ls-interaction-unit.log`. BUILD SUCCESSFUL.
2. **gating:** Desktop `:desktopApp:run -PewmAutoOpen=hires… -PewmW=1470 -PewmH=900` — capture Content multiline (2+ lines visible in field + preview), Export large variant, About large variant. Notes in `{SCRATCH}/desktop/NOTES.md`. Safe props only.
3. **gating:** Compile three hosts; Android phone + ≥840 when device up; iOS compile. Device absence → `{SCRATCH}/device-status.txt`, not fake shots.
4. **evidence:** ACSP result/verification/review; hard-door table; commit hashes; parity-shots copy optional.
5. **evidence:** Research checklist matrix flipped in this plan file.

---

## Non-goals

- ADR-0026 breakpoint churn; three-zone left library return  
- SupportingPaneScaffold / Nav3 SceneStrategy / FoldingFeature  
- Parallel design system  
- git push unless ordered  
- Full XCUITest morph suite if toolchain blocks (compile + shared path still required)

---

## Assumed scope (files / areas)

- `shared/.../ui/compose/TextContentOption.kt`, `SliderOption.kt`, filmstrip  
- `shared/.../ui/about/AboutScreenShell.kt`, `OpenSourceScreen.kt`  
- `shared/.../ui/save/SaveExportSheetShell.kt` (+ options/preview)  
- `shared/.../ui/compose/TemplateListSheet.kt`  
- `desktopApp/.../DesktopWindow.kt` (+ menus/shortcuts)  
- `shared/.../render/WatermarkCellComposer.kt` (verify only unless bug)  
- Tests under `shared/src/commonTest` / `desktopTest`  
- Research: `2026-08-10-large-screen-desktop-interaction-debt.html`

---

## Implementation approach

Ship in **batches A→F** with one umbrella DoD. Prefer shared helpers (`WatermarkTextFieldDefaults`, `sliderStep`, `aboutLayoutClass`) over copy-paste. Keep `formPath` / `layoutClass` branches explicit. Desktop shortcuts are host-edge; shared UI stays CMP. Every batch ends with compile + targeted tests; Desktop shots at end of A, B, C, D minimum.

---

## Task checklist

### A — Input fidelity (P0)

- [x] A1 Form inline multiline (minLines, no singleLine trap)
- [x] A2 Shared text-field defaults form ↔ sheet
- [x] A3 Contract unit tests (multiline + no silent singleLine)
- [x] A4 Preview/raster proof with `\n`
- [x] A5 Phone path regression check

### B — Sheets large-screen (P1)

- [x] B1 Export ≥840 variant (center dialog and/or dual-pane)
- [x] B2 Template sheet/dialog large-screen max-width / placement
- [x] B3 Compact sheet path preserved

### C — About (P1)

- [x] C1 Content max-width / readable measure ≥840
- [x] C2 Dev + Designer side-by-side ≥840
- [x] C3 Optional list\|detail or section density
- [x] C4 Desktop store/feedback/rating sensible edge

### D — Desktop host (P1)

- [x] D1 Keyboard open / save (export entry)
- [x] D2 Icon drop or paste where applicable
- [x] D3 Recent directory optional
- [x] D4 autoOpen remains for agent E2E

### E — Pointer density (P1–P2)

- [x] E1 Slider keyboard ± / Shift±
- [x] E2 Slider scroll wheel when focused
- [x] E3 Filmstrip arrow keys (P2)
- [x] E4 Focus order / rings (P2)

### F — Viewport polish (P2)

- [x] F1 Desktop draft preview bucket policy tweak (if cheap)
- [x] F2 OSS/long-text max-width ± TOC
- [x] F3 HEX paste normalize

### Closeout

- [x] Z1 Desktop shots: multiline + export + about
- [x] Z2 Android/iOS residual
- [x] Z3 ACSP Accept + local commits; no push
- [x] Z4 Flip this checklist + research status

---

## Safety (mandatory)

- No global `CGEvent` / `click at` / system keystrokes to arbitrary apps.
- Desktop open-image for agents: **only** `-PewmAutoOpen` (user shortcuts are in-app).
- Do not shut down owner emulators/sims unless ordered.
- Local commit OK; **push only on owner order**.

---

## Uncertainty Policy

- **Safe:** multiline form field; about dual card; export centered dialog; in-window key bindings; pure step helpers.
- **Block:** export redesign requires session/export API rewrite; About dual-pane needs new navigation graph beyond layoutClass; shortcut conflicts with Compose Desktop IME — document and ask.
- Do not mark umbrella goal done if only A ships.

---

## ACSP framing (when publishing)

**Title:** Large-screen & Desktop interaction complete  
**Session slug example:** `YYYYMMDD-HHMMSS--ls-desktop-interaction-complete`  
**Hard doors:** H1–H8 above in `task.md` / `task.json`  
**Worker:** implement batches; Desktop evidence; local commit  
**Coordinator:** visual Accept on H1/H4/H5 minimum before done; revise if phone-regression or singleLine remains  

Optional: split ACSP per batch (A, then B+C, then D+E+F) **only if** each session’s DoD still maps to umbrella checklist; final Accept only when umbrella DoD complete.

---

## Status matrix (live)

| Batch | State |
|---|---|
| Research report | done |
| This goal/plan | **authored** |
| A Multiline P0 | **done** |
| B Export/Template | **done** |
| C About | **done** |
| D Desktop host | **done** |
| E Pointer | **done** |
| F Polish | **done** |
| ACSP / Accept | worker → review |
