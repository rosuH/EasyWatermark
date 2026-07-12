# ADR-0017: Shared session ViewModel + ports + CMP-first UI

**Status:** Accepted (2026-07-12)  
**Plan ref:** `docs/superpowers/plans/2026-07-12-shared-business-state-machine-plan.md`  
**Supersedes (scope only):** S4d-191 “no shared ViewModel without named consumer” — consumers are now explicit (Android / Desktop / iOS CMP)

## Context

- Data layer (repos, models, three Editors) is already `commonMain` and consumed on three platforms.
- Product **session** state (route, selection, export progress, back) still lives mainly in Android `MainViewModel` (~1k lines) with parallel ad-hoc state on Desktop and iOS SwiftUI.
- Owner chose architecture **B**: multiplatform `androidx.lifecycle.ViewModel` + constructor-injected IO ports.
- Owner chose **CMP-first UI**: product screens in shared Compose Multiplatform; SwiftUI only for system edges (picker / share / save / permissions / app entry). Aligns with AGENTS.md “UI route of record”.
- Migration must not drop **Android SKILL** discipline or **runtime performance** of the production Android path.

## Decision

### 1. Session host

- Introduce `WatermarkSessionViewModel` in `:shared` `commonMain`, extending multiplatform `androidx.lifecycle.ViewModel`.
- Own platform-neutral session state (`StateFlow`) and intents; call existing `WatermarkConfigEditor` / `OutputPrefsEditor` / `TemplateEditor`.
- **Do not** move Android `ContentResolver` / `Bitmap` / `MediaStore` / native `WatermarkRenderer` into commonMain.

### 2. Ports (constructor injection)

Interfaces in commonMain, implementations per platform (same style as ADR-0005 / Editors):

| Port | Responsibility |
|------|----------------|
| `MediaLibraryPort` | List/query gallery items where applicable |
| `ImagePipelinePort` | Decode source + apply watermark → encoded artifact |
| `ExportStorePort` | Persist artifact (MediaStore / filesystem / Photos) |

Prefer constructor injection over new `expect/actual` factories.

### 3. CMP-first UI

- Product UI (Launch / Gallery / Editor / export sheet / templates) grows in **shared CMP**.
- **SwiftUI is not** the long-term product UI or session owner; shrink `WatermarkWorkflow` `@Published` product fields as CMP + ViewModel absorb them.
- Platform-native remains: pickers, share sheets, save-to-photos, window/app entry, and **renderer edges** (Android native production raster per ADR-0004).

### 4. Android SKILL & quality bar (non-negotiable during migration)

When any slice touches Android product UI or behavior:

1. Follow repo Compose migration skill (`.claude/skills/migrate-xml-views-to-jetpack-compose/`): baseline → change → visual verify → no silent layout drift.
2. Android **v2.10.0 production** remains visual/behavioral source of truth (ADR-0011).
3. Prefer unidirectional data flow: UI events → ViewModel intent → `StateFlow` → CMP.
4. Use lifecycle-aware collection on Android (`collectAsStateWithLifecycle` where applicable).
5. Keep Compose Previews for new/changed shared shells when practical.
6. No new business state in `remember` that must survive process/config recreation — session state lives in ViewModel.
7. Do not reintroduce LiveData; StateFlow-only (existing C1.1 direction).

### 5. Performance guarantees (non-negotiable)

| Rule | Detail |
|------|--------|
| **No regression on Android export path** | Android `ImagePipelinePort` wraps existing `generateImage` / sampling / native renderer; no drive-by reimplementation |
| **Heavy work off Main** | Decode/render/encode on background dispatchers; UI only observes progress `StateFlow` |
| **No double work** | One pipeline per export item; preview and export may share config source but must not allocate redundant full-res bitmaps beyond current production behavior |
| **Selection / config updates** | Cheap state updates; do not re-decode gallery on every watermark slider tick |
| **Strict goldens** | Touching Android raster/export requires local `WATERMARK_GOLDEN_STRICT` (or explicit owner rebaseline) when render bytes can change |
| **Budget** | Editor scroll/gesture jank and export wall-time must not regress vs pre-slice Android debug baseline for the same device/fixture set; call out measurements in slice notes when export/preview code moves |
| **Desktop/iOS** | Skiko path stays; do not force Android native renderer onto them |

### 6. Phasing

Ship in small PRs: toolchain → nav/selection VM → export ports → Desktop bind → iOS CMP bind → retire Android VM bulk. Each phase leaves Android green.

## Consequences

- ADR-0005’s “ViewModel split” direction is now the active program (common session VM + platform ports).
- iOS investment shifts from SwiftUI product chrome to CMP hosts + thin system glue.
- S4d-191 no longer blocks a shared ViewModel **when** ports keep IO out of commonMain and CMP consumers exist.
- Agents/implementers must treat §4–5 as acceptance criteria on every session-migration PR.

## References

- Plan: `docs/superpowers/plans/2026-07-12-shared-business-state-machine-plan.md`
- Android ViewModel KMP: https://developer.android.com/kotlin/multiplatform/viewmodel
- ADR-0004 (renderer), ADR-0005 (DI/ports), ADR-0011 (parity baseline)
