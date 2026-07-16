# Plan / Goal: Option C + C2 — ProductApp in commonMain + common 光栅 (Android export)

**Date:** 2026-07-12  
**Status:** **Accepted as goal plan** (owner: Option **C**, sub-path **C2** 「c2！」)  
**Binding decisions:**  
- Product strategy: `docs/parity/v2.10.0/alignment/u0-cmp-product-ui-decisions.md`  
- Raster: **[ADR-0018](../../adr/0018-option-c2-common-raster-android-export.md)** (reopens Android-native-forever addenda of ADR-0004 for **production routing only**)  
- Session: ADR-0017 (CMP-first + shared session VM)  
- UI route of record: AGENTS.md — shared CMP product UI; system edges only on platforms  

**Does not reopen:** U0 E02 (no off-Android in-app gallery); EXIF-strip-on-export (ADR-0009); DataStore plain per-platform factories.

---

## 0. Goal statement

Deliver **one maintainable product**:

1. **Product UI** lives in `shared/commonMain` as a **`ProductApp`** (Launch / Editor / About / export sheet / templates chrome), collected from **`WatermarkSessionViewModel` only**.  
2. **Watermark paint path (common 光栅)** is the **same algorithm** for **preview and export** on **Android, Desktop, and iOS** via `WatermarkCellComposer` + `composeOverBackground` (platform: decode, fonts/`TextRasterEnv`, encode, system I/O).  
3. Android **production export** eventually **stops** using native `WatermarkRenderer.build*Shader` / `compose` as the primary path (gated, measured, rebaselined — **not** byte-claim vs v2.10.0 native).  
4. Every Android UI touch follows **Android SKILL + Compose best practices** (below); performance gates from ADR-0017 hold.

**Success = maintainability + WYSIWYG (preview≡export engine) + three-platform one UI**, not FNV equality to historical native goldens.

---

## 1. Android SKILL & Compose best-practice contract (mandatory every slice)

These are **hard gates** for any PR that touches `:app` Compose or shared UI consumed by Android.

### 1.1 Android SKILL (migrate-xml → Compose discipline, adapted)

Source of truth for **visual/product behavior** remains **production v2.10.0** until a **signed** C2 export rebaseline says otherwise. For UI layout work:

| Step | Rule |
|------|------|
| Baseline | Screenshot or structural capture **before** change when UI chrome moves |
| Change | One coherent surface per slice (editor bottom, top bar, export sheet…) |
| Verify | Screenshot / `android layout` / device smoke after change |
| Delete | No orphaned parallel UI; no dual business owners |

(XML migration skill steps 4/8/9 map to: baseline → implement → visual verify → remove dead path.)

### 1.2 Compose / UDF (Android + commonMain)

| Rule | Detail |
|------|--------|
| **UDF** | UI → intent/`dispatch` → session/repo → `StateFlow` → UI. No business truth only in `remember { mutableStateOf }` |
| **Lifecycle** | Collect with lifecycle-aware APIs on Android (`collectAsStateWithLifecycle` where available); Desktop/iOS use stable session scope |
| **No dual state** | Config/export/nav from **session**; ephemeral UI only (sheet open, slider drag draft until apply) |
| **Stability** | Prefer immutable state, stable keys in lists, avoid unnecessary recomposition of full-res bitmaps |
| **Theming** | Material3 via shared/app theme; dynamic color stays Android capability edge |
| **a11y / testTag** | Preserve XCUITest / Compose test tags when moving hosts; don’t break fixture seam (E14) |
| **Previews** | New shared composables get `@Preview` **or** documented desktop/ios witness where Preview unavailable |

### 1.3 Performance (ADR-0017 / AGENTS)

| Rule | Detail |
|------|--------|
| Heavy work off Main | Decode, full compose, encode on `Dispatchers.Default`/`IO` (or platform equivalent) |
| No full-res export on every slider tick | Debounce / preview-quality path; export remains explicit or high-quality path |
| Android export wrap-not-rewrite | Until Gate 3 flip: ports wrap pipeline; after flip: still no main-thread raster |
| Bitmap lifetime | Don’t hold full-res bitmaps in composition locals without eviction |

### 1.4 What “best practice” does **not** mean

- Not pixel-identical to native Android goldens under C2  
- Not inventing gallery on iOS/Desktop  
- Not putting `android.graphics.*` into commonMain  

---

## 2. Architecture (target)

```text
                     commonMain
        ┌──────────────────────────────────────────┐
        │ ProductApp (CMP)                         │
        │  routes: Launch | Editor | About | …     │
        │  EditorBottomControls, shells, sheets    │
        │  collects WatermarkSessionViewModel      │
        │  slots: strings/painters + PreviewSlot   │
        ├──────────────────────────────────────────┤
        │ Common raster pipeline                   │
        │  composeTextCell / composeIconCell       │
        │  composeOverBackground                   │
        │  (same for preview bitmap & export)      │
        └──────────────────┬───────────────────────┘
                           │ inject
     ┌─────────────────────┼─────────────────────┐
     ▼                     ▼                     ▼
 Android                Desktop                 iOS
 TextRasterEnv          Skiko fonts             NSBundle fonts
 decode EXIF            ImageIO+EXIF            Skia EXIF-bake
 encode MediaStore      FS encode               Photos/Share
 PreviewSlot=           PreviewSlot=            PreviewSlot=
  common pipeline        common pipeline         common pipeline
 ExportPort=            ExportPort=             ExportPort=
  common pipeline        common pipeline         common pipeline
```

**Permanent platform-only:** pickers, share, save, permissions, Activity/Window/UIViewController entry.

---

## 3. Workstreams & phases

### Phase 0 — Policy & measurement harness (Gate 0 + Gate 1 start)

| ID | Deliverable | SKILL/Compose |
|----|-------------|---------------|
| P0.1 | ADR-0018 + this plan in force; AGENTS.md “do not route Android…” clauses **updated** to “gated by ADR-0018” | Docs |
| P0.2 | Golden policy note: which suites stay structural; which get **perceptual / rebaseline** after C2 | ADR-0010 delta |
| P0.3 | **Test-only** dual-path harness: same `WaterMark` + fixture → **native** export vs **common** compose; log dims, optional SSIM/IoU, **no production flip** | Off-main; no UI state |
| P0.4 | Capture Android **baseline** screenshots (editor + one export) for locale EN + zh if available | SKILL baseline |

**Exit:** harness green; owner sees sample native-vs-common visual delta (esp. CJK); go/no-go on Gate 2.

### Phase 1 — ProductApp skeleton (UI Option C, raster still dual)

| ID | Deliverable | SKILL/Compose |
|----|-------------|---------------|
| P1.1 | `ProductApp` / screen state in commonMain (hand-rolled routes OK; Nav3-CMP optional later) | UDF from session |
| P1.2 | Android: `ComposeMainActivity` hosts ProductApp; `EditorScreen` becomes thin binder (resources + slots) | Baseline → change → screenshot |
| P1.3 | Desktop/iOS: Window / single Compose root call **same** ProductApp; delete bring-up-only chrome where ProductApp covers it | No dual session |
| P1.4 | Shared string/painter injection API (bags first; Weblate codegen follow-up) | **Superseded by ADR-0019 / S-i18n plan** — product UI uses `Res.string` / `Res.drawable` (not bags/codegen) |
| P1.5 | PreviewSlot interface; Android still **may** use native canvas **behind** slot until Phase 2 | Document temporary dual paint |

**Exit:** three platforms open the **same** ProductApp composable graph; config still session-only; Android export still native (or behind flag off).

### Phase 2 — Android preview → common 光栅 (Gate 2)

| ID | Deliverable | SKILL/Compose |
|----|-------------|---------------|
| P2.1 | Android `TextRasterEnv` production path (not test-only fonts only) | Off-main cell build |
| P2.2 | PreviewSlot implementation: decode sample → common cell → composeOverBackground → display `ImageBitmap` **or** equivalent Compose draw | Debounce slider; no export-on-tick |
| P2.3 | Feature flag `useCommonRasterPreview` (default on for debug; owner for release) | |
| P2.4 | Device smoke EN+zh; screenshot vs Phase 0 baseline (expect CJK drift — recorded, not failed as “bug”) | SKILL verify |
| P2.5 | Performance: scroll/slider jank check; cap work with preview max edge length | Perf gate |

**Exit:** Android editor preview uses common raster when flag on; export still native **or** flag-separated.

### Phase 3 — Android export → common 光栅 (Gate 3) — **C2 core**

| ID | Deliverable | SKILL/Compose |
|----|-------------|---------------|
| P3.1 | `AndroidExportPipelinePort` / `generateImage` path builds output via common pipeline + platform encode | IO dispatcher |
| P3.2 | WYSIWYG: preview path and export path call **same** pure function(s) (same WaterMark, same image-space sizing) | |
| P3.3 | Golden policy execute: rebaseline or perceptual suite; **strict legacy FNV not required to match pre-C2** | |
| P3.4 | Release smoke: export JPEG/PNG, batch, icon mode, CJK text | |
| P3.5 | Flag `useCommonRasterExport` → default on after soak | |

**Exit:** production export uses common 光栅; native builders unused by production (or dead-code quarantine).

### Phase 4 — Cleanup & freeze (Gate 4)

| ID | Deliverable |
|----|-------------|
| P4.1 | Remove or isolate dead native production paths; update AGENTS.md permanently |
| P4.2 | iOS/Desktop delete remaining parallel control lists / debug summary chrome if ProductApp complete |
| P4.3 | Docs: CONTEXT.md + exception registry E11/E12 wording (engine unified intent; metrics residual only where fonts differ) |
| P4.4 | Optional: shared string codegen from Weblate | **Done via ADR-0019 composeResources** (not codegen); Weblate retarget still post-`master` |

---

## 4. Task checklist (goal harness)

- [x] **P0** Policy + dual-path measurement harness; AGENTS ADR-0018 pointer  
  - Residual: device EN/zh **baseline screenshot pack** not captured (Robolectric dual-path PNG/metrics only).  
- [x] **P1** ProductApp commonMain **called** from Android (`LaunchScreen`/`EditorScreen`), Desktop (`DesktopWindow`), iOS (`IosProductRootHost`); session-owned business state  
  - Residual: gallery dialog / save sheet / About remain platform-hosted slots; not a single Nav graph on Android.  
- [x] **P2** Android preview common raster (flagged, DEBUG default on) + CLAMP drag under common path; produceState off Main  
  - Residual: device zh/EN screenshot smoke not re-run in this round.  
- [x] **P3** Android export common raster behind flag + dual-path + **exportOne** entry test  
  - Residual closed by **P3.5 (2026-07-13):** `CommonRasterFlags` default **on** for preview+export (debug **and** release). Strict FNV rebaseline not required under C2 (perceptual/dual-path; legacy goldens still call native oracle directly).  
- [x] **P4** Docs freeze + native **quarantine as flag-off** (not deleted)  
  - Done: CONTEXT.md cell vocabulary; exception registry E11/E12 C2 wording; `WatermarkRenderer` KDoc marks flag-off fallback.  
  - Residual: **delete** native body only after owner Gate 4 (optional); P4.2 Desktop/iOS chrome thin-out optional; **P4.4 closed by ADR-0019 composeResources** (Weblate retarget still post-`master`).  
- [x] **Cross-cut** no business-only-in-remember for config; heavy work off Main; no full export per slider tick; docs-with-code  

## Deviations
- ~~Release builds keep flags off~~ → **P3.5:** both flags default **true** (debug+release).
- Native `WatermarkRenderer` **quarantined as flag-off fallback** (not deleted — Gate 4 delete is owner-gated).
- Device smoke **2026-07-13:** export sheet captures under `docs/parity/v2.10.0/captures/c2-p35-smoke-2026-07-13/` (Android + iOS; Desktop headless + compile). Dual-path Robolectric remains CJK/engine delta evidence.
- Android hosts ProductApp **inside** existing screens/Nav destinations (slots for system edges); not a full Activity-only ProductApp swap of gallery/save.
- **Export panel (2026-07-12/13 C2):** Desktop + iOS open shared `SaveExportSheetShell` from editor Save (Android Compose parity). Final write/share remain platform edges (Photos / FS / share sheet).

---

## 5. Verification plan (run before claiming goal complete)

1. **Docs:** ADR-0018 Accepted; this plan Accepted; U0 file marks C2; AGENTS no longer forbids common Android production routing without pointing at 0018.  
2. **Android UI:** `./gradlew :app:assembleDebug :app:testDebugUnitTest`; touched screens screenshot or layout dump vs baseline (layout structure; after P3, CJK delta **documented**).  
3. **Common raster:** dual-path harness exists and runs; post-P3 export path unit/instrumented test calls **shipped** export entry (not a reimplemented painter in the test).  
4. **Desktop:** `./gradlew :shared:desktopTest :desktopApp:compileKotlin` (+ headless if present).  
5. **iOS:** `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test`; xcodebuild iosApp; XCUITest fixture seam still green (E14 residual OK).  
6. **Source audit:** ProductApp in commonMain; Android/iOS/Desktop hosts thin; `generateImage`/export port does **not** call native build*Shader for production when C2 flag on; no dual session.  
7. **Compose practice audit:** lifecycle collection on Android; no config owned only in Compose local state; PreviewSlot not blocking Main with full-res work.  

Evidence root: goal implementer `{SCRATCH}/` (logs + optional `cmp-ui-align/` screenshots).

---

## 6. Non-goals

- Byte-exact FNV parity with pre-C2 native goldens  
- Off-Android MediaStore gallery (E02)  
- Real PHPicker grid-cell automation if toolchain still broken (E14)  
- Full Weblate multiplatform resources in P0 — **closed later by ADR-0019** (composeResources; Weblate path retarget deferred to post-`master`)  
- Rewriting Desktop/iOS engines that already use commonMain (only align call sites)

---

## 7. Risks

| Risk | Mitigation |
|------|------------|
| CJK shock on Android users | P0 visual pack; release notes; optional staged flag |
| Preview jank after common raster | Preview max dimension + debounce; never full export per frame |
| Golden CI break | Move strict FNV off PR CI (already local-only for some); perceptual in P3 |
| Scope explosion (ProductApp + raster together) | **Serialize:** P1 UI structure can ship with temporary native preview; P2/P3 raster after P0 harness |
| Dual state regression | Session-only config; checklist item on every PR |

---

## 8. Suggested first implementation slice (after plan accept)

**P0.3 only:** Android test-only dual-path measurement (native vs `composeTextCell`/`composeIconCell` + `composeOverBackground`) writing artifacts under `build/` or `{SCRATCH}/p0-dual-path/`. **No** production routing. Prove fonts/`TextRasterEnv` on Android production classpath path in test. Update AGENTS one paragraph for ADR-0018.

---

## 9. Relationship to prior work

| Prior | Relation |
|-------|----------|
| U0–U5 CMP product UI | Structure/session foundation; ProductApp is the consolidation |
| ADR-0017 session | Unchanged; ProductApp consumes it |
| ADR-0004 S4d-8/17/190 | **Production ban superseded by ADR-0018**; history retained |
| Desktop/iOS common raster | Already target paint path; Android joins under C2 |

---

*End of plan*
