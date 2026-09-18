# ADR-0032: E2E test case map and layered harness

**Status:** Accepted (owner 2026-08-19)
**Related:** ADR-0010 / 0010-c2 (golden policy — unchanged; goldens are not this harness), ADR-0011 (parity baseline; `docs/parity/v2.10.0/inventory/screens.md` is the map's ancestor), ADR-0026 (adaptive layout — dual-pane morph is a mapped edge), ADR-0031 (no third-party path classifiers; no dynamic required checks)

## Context

The repo has e2e-shaped assets but no system: iOS XCUITest (`PickerFlowUITests`, 21 tests, the only UI-driving product suite, driven through the `-uiTestFixtureImage` seam), Android UI Automator journeys living only in `:macrobenchmark`, Desktop `--headless` spine + `ewm*` window props, four disconnected perf assets (macrobenchmark, `HundredImageSeedTest -e ewmPerfHold`, iOS `DEVICE_PERF_*`, desktopTest timed benches), a manual screen×state inventory (`screens.md`), and `testTag`s across the shared UI. Missing: a machine-readable map of screens/paths, a cross-platform UI-driving layer, and change→path selective execution. Guiding constraint carried over from prior owner work: **reuse existing dump/tag/drive capabilities; do not invent a new test framework or click engine.**

## Decision

### 1. Map is data: `docs/testmap/map.yaml` is the single source of truth

Nodes are screens/overlays/sheets/dialogs; Editor tabs are **states** of the editor node, not nodes. Schema:

- `nodes{id, source, states[], screens_ref?}` — `screens_ref` back-links `screens.md` state IDs for traceability.
- `edges{id, from, to, trigger{kind: tag|system|prop, value}, platforms{android|ios|desktop: {drive: real|seam|none, via?}}, owners[], priority: core|normal|edge, cases[{layer, ref, platform?, dimension?}]}`.

The `drive` field is the honesty label: edges crossing system UI (PHPicker/photo picker cells, share sheets, permission prompts, FileDialog) are `seam` (fixture seam / share-in / `ewmAutoOpen`) or `none` — never claimed as `real`. First version ships **17 edges**: 5 shell-level (pick→editor, share-in→editor, launch→about, editor→about, launch→gallery Android), 6 editor sheets (template, text edit, custom color, save/export, icon picker, add-more picker), 3 export chain (save success, system share, export-failure recovery), 3 platform-specific (Android crash recovery, iOS Library Read upsell, ≥800dp dual-pane morph). New edges must ship with drive labels and owners.

### 2. Four execution layers; every case is tagged with one

- **L0 contracts** (exists): commonTest reducer/nav/catalog tests.
- **L1 shared Compose UI** (this ADR adds): `runComposeUiTest` (v2 API) driving the real shared Compose tree with fake ports (`ExportPipelinePort`/`MediaLibraryPort`) + temp-dir DataStore/Room. Lives in a new `shared/src/uiTest` srcDir merged into **desktopTest + iosTest only** (the `skikoTest` pattern) — it must not enter `commonPureTest` (Android host JVM has no render environment). Rides the existing PR gates (`:shared:desktopTest`, `:shared:iosSimulatorArm64Test`) from day one. First batch: 6 cases — seam-feed→editor, tab/option interaction, template save/apply/delete, text edit sheet, export via fake port, About overlay round-trip.
- **L2 platform system edges**: iOS XCUITest (exists, stays residual/non-PR); Android by promoting the `:macrobenchmark` UI Automator journey code; Desktop click layer **deferred** (decide after P1: Compose Desktop UI test vs extending `ewm*` props into a script channel).
- **L3 perf**: existing perf assets are mapped as `dimension: perf` case variants on the same edges. Observational only; no SLO gates initially.

**Android L1 coverage stance:** the shared tree is verified on two targets (desktop Skiko + iOS simulator); Android does not get a third semantic layer (no Robolectric Compose suite). Android-specific risk lives at system edges = L2/agent. This residual is accepted and recorded here.

### 3. Guard test: map↔code consistency is a PR gate from day one

A pure structural desktopTest (no UI rendering; repo-root file resolution per `ProductShellHostOverlayTest`) asserts: every node maps to a `ProductShellNav.Route`/real screen; every `trigger.tag` string exists in shared UI source; every `cases[].ref` names an existing test. Renaming a tag or adding a route without updating the map turns CI red.

### 4. Selective execution: coarse, repo-local, informational

`scripts/e2e-select.sh`: `git diff` → source-set/module buckets → map lookup → prints the suggested layer/task list. Local manual first; **never** a dynamic required check and never via third-party path-classifier Actions (ADR-0031). Granularity starts coarse (commonMain UI → all L1; androidMain/iosMain/desktopMain edge → that platform's L2); edge-level ownership refinement only after the coarse loop is proven.

### 5. Agent role: explore → promote

Agents (adb + `android layout` on Android, simctl+XCUITest seam on iOS, `ewm*` props on Desktop) walk the map for exploratory verification of changes lacking scripted cases; verified paths get promoted to scripted L2 cases and written back into `cases[]`. Walk protocol lives in `docs/agents/e2e-walk.md` (promoted to a repo skill once stable). Edges scripted drivers cannot reach (real picker cells, system share) remain agent/manual permanently.

### 6. Generation and provenance

A Python generator in `scripts/` renders `map.yaml` → mermaid graph + case list + per-platform coverage table into `docs/testmap/` (committed). The guard test stays assertion-only. `screens.md` is frozen as parity-era evidence; `screens_ref` is one-way — no dual maintenance.

### Implementation constraints

- `compose.uiTest` dependency addition is its own J4 slice (record rollback HEAD; no bundling with other catalog moves); version rides the existing CMP pin (`1.12.0-rc01`), using the v2 `ComposeUiTest` API.
- Phasing: **P0** map + inventory mapping (zero new deps) → **P1** L1 layer + guard test → **P2** selection script → **P3** agent explore→promote protocol.

## Considered and rejected

- **New e2e framework / Maestro / bespoke click engine** — contradicts the reuse constraint; existing tags+seams+drivers suffice.
- **State-level map nodes** — 40+ states explode the edge count; most state transitions have no independent driver.
- **Code-derived map (no file)** — scanning `ProductShellNav`+tags can't express drive labels, owners, or case bindings; the guard test gives equivalent rot protection.
- **L1 in commonTest with `commonPureTest` exclusions** — exclusion rules are a trap (`ContentEditorThemeTest` precedent); srcDir merge is the established pattern.
- **Robolectric Compose L1 for Android** — a third copy of the same semantic coverage for the same shared tree.
- **Reusing `skikoTest` for L1** — mixing UI-driving tests into the bitmap-primitive source set muddles both.
- **Selection script as a dynamic PR gate** — isomorphic to the required-check classifier trap ADR-0031 removed.
- **Deleting or dual-maintaining `screens.md`** — breaks parity provenance / guaranteed drift.

## Consequences

- Every PR touching shared UI routes/tags must update `map.yaml` in the same PR (guard-enforced). Map upkeep is the price of a trustworthy map.
- PR gate duration grows by the guard (seconds) + 6 L1 cases on two existing jobs; no new CI jobs.
- Android has no PR-gated semantic UI layer; its e2e confidence comes from the two-target shared tree plus non-gated L2/agent runs — an explicit, recorded residual.
- `docs/testmap/` gains committed generated artifacts; regeneration is manual via the script (no build hook).
- Deferred, each needing its own decision when reached: Desktop L2 click layer choice; perf thresholds/SLOs; edge-level selection granularity; promoting `e2e-walk.md` to a skill.

## Amendment 2026-08-21 — CLI/console split and L1 visual witness

The run engine is `scripts/testmap_run.py`: task registry, process-group lifecycle, JUnit parse, and the `docs/testmap/runs/` JSON+log schema. `scripts/e2e-run.sh` is the foreground CLI (live tee, `state: stopped` on interrupt). `scripts/testmap_console.py` is HTTP-only over that engine (`127.0.0.1`). CLI and console share records and therefore map badges. No new CI gate.

L1 may dump `captureToImage()` PNGs under `shared/build/l1-witness/` for human viewing. Evidence only — no pixel, hash, or golden assertion (ADR-0010 unchanged). File write is best-effort on iOS simulator; desktopTest is the witness target.

## Amendment 2026-08-22 — console copy (en/zh)

Operator-facing titles are not derived from method names. `docs/testmap/copy.yaml` holds `en`/`zh` for every node, edge, and `cases[].ref`. The generator embeds that catalog; the console switches language locally. Guard + generator require a title for every mapped ref. Chrome strings live in the HTML template I18N table. This is not product-app i18n (ADR-0019).

## Amendment 2026-08-22 — L1 completes remaining map edges

Every mapped edge now has at least one L1 case at the shared-UI layer. Honesty stays:

- in-app gallery is the real `GalleryDialogShell` plus a fixture listing
- add-more chrome (`sharedComposeAddMoreButton`) appends a second fixture — not FileDialog / PHPicker
- icon option is the real `IconWatermarkOption`; pick is seamed to `mem://l1-icon`
- export “View in gallery” is the desktop share substitute, not a system share sheet
- `RecoveryScreen` close is the shared screen, not `MyApp.recoveryMode`
- Library Read upsell uses the same `EwmConfirmDialog` tags/strings as iosMain, not PhotoKit
- share-in is a preloaded selection, not `ACTION_SEND`

System pickers, FileDialog, share sheets, and the Android crash gate stay `drive: none` or `seam`. Do not claim those as `real`.

## Amendment 2026-08-22 — console run target

The console Run tab picks the **runner** (Desktop / iOS / Android). Canvas OS chips remain map filters only. Desktop stays the default host-JVM + Skiko path. iOS host L0/L1 is `:shared:iosSimulatorArm64Test` (heavy). Android host L0 is `:app:testDebugUnitTest` — still no Android L1.

## Amendment 2026-08-22 — console may attach or boot a device

The earlier “L2 stays a copy-command / do not one-click an emulator” rule is withdrawn for the **local** console (owner).

The Run tab and an edge ▶ on iOS/Android may:

- attach to an already-ready physical device, Android emulator, or iOS Simulator
- boot a shutdown iOS Simulator or Android AVD when the operator picks `auto` or an explicit offline target

Still in force:

- Not a CI gate (ADR-0031). Device L2 never becomes a required PR check.
- Do **not** shut down an already-live Android emulator or iOS Simulator (standing order 2026-07-11), including ones this console booted. Stop kills the test process group only.
- Do **not** route L2 through AGP managed virtual devices (`pixel6Api31…`). Gradle tears those down after the task.
- The UI must show the chosen device and that the run is heavy (this machine has frozen under emulator + build).
- Android still has no L1. `:app:connectedDebugAndroidTest` is the instrumented golden/parity suite. Mapped `ProductJourneys` / `ProductBaselineProfileGenerator` run through `:macrobenchmark:connectedBenchmarkAndroidTest` (method-level Android L2 is suite-grained). Those connected tasks take `--serial` and `-Pandroid.testInstrumentationRunnerArguments.class=`; they reject Gradle `--tests` (`DeviceProviderInstrumentTestTask`).
- iOS XCUITest uses `xcodebuild … -only-testing:iosAppUITests/PickerFlowUITests` (edge runs may filter methods). Simulator keeps `CODE_SIGNING_ALLOWED=NO`. Physical iOS needs a signing team.
- Launching a device does not flip `drive: none` / `seam` to `real`. System picker cells, FileDialog, and share sheets stay dishonest if claimed as product-control-driven.

## Amendment 2026-08-23 — live preview and key-node frames

L1 desktop `captureToImage()` now writes a **live preview** plus **key-node frames** under `docs/testmap/artifacts/` (gitignored; reset at the start of each console/CLI run):

- `live/preview.png` — overwritten as the suite walks; console polls `/api/status.live` and shows the frame on the Run tab
- `keyframes/<seq>-<test>-<tag>.png` — one PNG when a product tag appears (`waitForTag`) and again at the existing end-state witness
- End-state witnesses still go to `shared/build/l1-witness/` for the confirm flow

Not a golden (ADR-0010). Not a video stream: frames land after Compose idles on a tag, and the console refreshes about once a second. iOS writes stay best-effort. The HTTP paths are basename-only (`/artifacts/live/preview.png`, `/artifacts/keyframes/<file>.png`).

## Amendment 2026-08-23 — console drive labels are sentences

`map.yaml` still stores `real | seam | none`. The console must not show those tokens (or calques like 真实 / 缝 / 不可脚本). Operator-facing copy:

| YAML | zh | en |
|---|---|---|
| `real` | 能直接点 | Can tap |
| `seam` | 跳过系统框 | Skips system UI |
| `none` | 只能人手测 | Hands only |

The filter group is **怎么测 / How we test**, not DRV. Hover text and the legend spell out the rule: tap the app, skip a system dialog, or a person has to do it. Generated mermaid/coverage keep the YAML keys for git diff.

## Amendment 2026-08-23 — change-to-verify operating contract

After a product change, verification is this loop — not a new framework, not a new click engine, not a CI gate for `e2e-select` (ADR-0031 unchanged).

**Authority** is this ADR. **Agent entry** is [`eval/README.md`](../../eval/README.md). Explore → promote stays [`docs/agents/e2e-walk.md`](../agents/e2e-walk.md). Watch stays the testmap console. Do not promote `eval/` to a repo skill until that playbook is stable.

**1-to-1** means change class → `e2e-select` coarse buckets → `owners[]` → those edges’ existing L0–L3 cases. Running every L1 on a shared-UI owner is **corresponding**, not a miss. A new case is added only when no mapped edge or L3 dimension exists. Not a new journey per diff; not pixel 1:1 (ADR-0010).

**Heuristic pass** is the same verification, not a second suite. Mandatory when select suggests L1, L2, L3, or agent/manual. L0 / guard may be script-only. Natural-language expected effect = this change’s description (PR / session / commit) plus the edge’s `copy.yaml` title. Script pass/fail remains authoritative for “the path works.” There is no `--heuristic` runner.

**Reports:** one committed blank [`eval/templates/verify.md`](../../eval/templates/verify.md). Fill it locally under `docs/testmap/runs/` (gitignored). Perf is an optional section when select suggested L3 or the change is a performance change: copy numbers if the ref emits them, else write “no numeric emission”; compare a previous local run if one exists, else “no baseline.” Numbers do not own pass/fail. No SLO.

**Perf binding** is the same `owners[]` + hanging L3 `cases[]`. If a matched edge has no L3 dimension, add an observational case first, then run. Unmapped benches (e.g. iosMain `DEVICE_PERF_*`) are not 1-to-1 until they hang on an edge. Do not run every L3 for every perf change. There is no `l3-*` console task today — Gradle / existing suite ride-alongs are enough.

Unchanged: four layers, honesty labels, no Android L1, L3 not a PR SLO gate, Desktop L2 click layer still deferred.
