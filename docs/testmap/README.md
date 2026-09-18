# E2E test map

`map.yaml` is the single source of truth for EasyWatermark product paths (historical test-map contract; current ADR-0032 is splash fade). Nodes are screens, overlays, sheets, and dialogs. Editor tabs (Content / Style / Layout) and layout class (compact / medium / expanded / wide) are **states** of the `editor` node, not nodes.

Human titles (English / Chinese) live in [`copy.yaml`](copy.yaml), keyed by node id, edge id, and `cases[].ref`. The console shows those titles; the `ref` stays as a copyable subtitle. The header **EN / 中** switch persists in `localStorage` (`ewm-testmap-lang`) and defaults to Chinese when the browser language starts with `zh`. Adding a case without a copy row fails generation and `TestMapGuardTest`.

Spec: [`historical-adr-0032-e2e-test-map-and-harness.md`](historical-adr-0032-e2e-test-map-and-harness.md).

The default agent adapter is **Agent Device** (ADR-0037): `edge:<id>@android#agent`, and `edge:<id>@ios#agent` when that edge’s iOS `drive` is not `none`. Compose Desktop stays L1/`desktopTest` — not Agent Device. Payloads stay 1:1 with the **29** `map.yaml` edges (15 nodes); that is not a second topology. Drive labels stay `real|seam|none` even when an agent walks a system picker.

Artemis is historical and **deprecated** (P4). [`artemis-cases.json`](../testing/artemis-cases.json) is read-only provenance; CLI still accepts `#artemis`. Replay or SDK completed is not a product pass. Ingest layers stay `execution` / `script_checks` / `agent_observation` / `independent_review` / `human_confirmation` / `business`. Do not mint human confirmation. Add More stays business-failed on `20260912T140000-000c3f44`. See [`artemis-binding.md`](artemis-binding.md).

`screens_ref` on a node is a one-way back-link to the frozen parity inventory [`docs/parity/v2.10.0/inventory/screens.md`](../parity/v2.10.0/inventory/screens.md). Do not dual-maintain that file.

## Regenerate

From the repo root (Python 3, stdlib only — no PyYAML):

```
python3 scripts/generate_testmap.py
```

This writes `map.mmd`, `coverage.md`, and `map.html`. Those files are generated; do not hand-edit them.

`map.html` is the browsable layered view (filter by layer / platform / drive / priority, search refs). `coverage.md` is the diffable text view of the same map. Both are generated from `map.yaml`.

## Drive vocabulary

Each edge declares a per-platform `drive` in `map.yaml`. Those keys stay `real | seam | none`. The console shows a human sentence, not the key:

| YAML | Console (zh / en) | Meaning |
|---|---|---|
| `real` | 能直接点 / Can tap | The script taps the app’s own control (shared Compose tag or in-app dialog). |
| `seam` | 跳过系统框 / Skips system UI | Proven only by skipping system UI (share-in, `-uiTestFixtureImage`, `-PewmAutoOpen`, permission-dialog taps). |
| `none` | 只能人手测 / Hands only | No script reaches this platform edge. A person or agent has to do it. |

System photo-picker cells, AWT `FileDialog`, system share sheets, and OS permission prompts are never `real`. L1 may still cover the shared chrome on those edges (in-app gallery, add-more button, icon option, recovery screen, Library Read dialog, export “View in gallery”) without claiming the system UI.

Desktop `-PewmAutoOpen` / `ewm.desktop.autoOpen` is a **prop-backed seam** on `pick-to-editor`, not `real`: it injects file paths and skips the native FileDialog. The product trigger remains the `launchPickImageButton` tag; `via` records the seam.

## Guard

`TestMapGuardTest` (`:shared:desktopTest`) asserts map↔code consistency: every `trigger.kind: tag` value exists in shared UI source, every node `source` path exists, and every `cases[].ref` token exists in repo source. Renaming a tag or adding a route without updating `map.yaml` fails CI.

## Selective execution

[`scripts/e2e-select.sh`](../../scripts/e2e-select.sh) classifies a git range (default: working tree vs `HEAD`) into coarse buckets, matches `owners[]`, and prints a suggested L0/L1/L2/L3 run list. Informational only — never a CI gate (ADR-0031 / ADR-0032 §4).

```
scripts/e2e-select.sh
scripts/e2e-select.sh master...HEAD
scripts/e2e-select.sh --help
```

## After a product change

Follow [`eval/README.md`](../../eval/README.md): `e2e-select` → run the suggested scripts → heuristic when select said L1/L2/L3 or agent/manual → fill [`eval/templates/verify.md`](../../eval/templates/verify.md) under `docs/testmap/runs/` (gitignored).

When a change touches edges without L1/L2, or `drive: none` on every platform, also follow [`docs/agents/e2e-walk.md`](../agents/e2e-walk.md): walk the map, then promote a repeatable path to a scripted L2 case and write it back into `cases[]`.

## Local runner (CLI + console)

[`scripts/testmap_run.py`](../../scripts/testmap_run.py) is the shared engine: task registry, process-group stop (SIGTERM, then SIGKILL), JUnit XML → per-case records, and gitignored run artifacts under `docs/testmap/runs/` (`<timestamp>-<sha>.json` + `.log`). Python 3 stdlib only; informational; never a CI gate.

CLI and console share the same records. Edge badges on `map.html` read that directory, so a CLI run shows up in the console History the next time the page polls.

### CLI

[`scripts/e2e-run.sh`](../../scripts/e2e-run.sh) runs the queue in the foreground and tees child output to stdout and the record log.

```
scripts/e2e-run.sh --list
scripts/e2e-run.sh guard
scripts/e2e-run.sh guard l1-desktop
```

Ctrl-C stops the current process group, writes the record with `state: stopped`, and exits nonzero. Any failed task also exits nonzero. Gradle test tasks add `--rerun-tasks` so JUnit XML is rewritten (an UP-TO-DATE test task leaves no modified `TEST-*.xml`).

### Console

[`scripts/e2e-console.sh`](../../scripts/e2e-console.sh) is a thin HTTP layer over the same engine. It serves `map.html` plus a localhost JSON API (`http://127.0.0.1:8931`).

```
scripts/e2e-console.sh
scripts/e2e-console.sh --port 8931
```

Opened as a file, `map.html` stays a static layered viewer (live chrome stays hidden). Through the server, the default UI is the product map plus **three live slots** (Android, iOS Simulator, Desktop). Mobile Agent Device runs **android+ios in parallel**. Android/iOS prefer H.264 (`GET /api/device-video`); Desktop is a still of the Compose window when present. Missing WebCodecs/idb falls back to `GET /api/device-frame`. Native scrcpy is an optional button, not auto-started. Runs are started from the CLI or by telling an agent (`e2e-testmap` / `scripts/e2e-run.sh edge:<id>@android#agent`). Advanced (collapsed) still has the old Edge / Run / History panels. If the server later dies, the page shows an offline banner and keeps probing until `e2e-console.sh` is started again.

Watching never boots a device. Stop kills the test process group only. Replay / batch success is `review_required`, not a product pass; Confirm is human-only.

- **Pause queue** does not suspend the current Gradle process (Gradle cannot be safely paused mid-test). It only holds the next queued task.
- **Stop** sends SIGTERM to the current process group.
- After a run the Gradle daemon is left running so repeats are faster. Server shutdown kills a running child; it does not `./gradlew --stop`.
The Run tab has an OS target (Desktop / iOS / Android). That is the runner. The canvas OS chips only filter the map. iOS/Android also have a **device** picker (`auto` or an explicit id). `auto` prefers a ready physical device, else a ready emulator/Simulator, else boots one. Already-live emulators/Simulators are never shut down (including ones this console booted). Stop kills the test process group only.

- **Desktop:** `:shared:desktopTest` (L0/L1) and the headless spine. Default.
- **iOS:** host L0/L1 is `:shared:iosSimulatorArm64Test`. L2 is `xcodebuild` `PickerFlowUITests` on the selected Simulator or iPhone.
- **Android:** host L0 is `:app:testDebugUnitTest` (no L1; `--tests` is valid here). L2 is `:app:connectedDebugAndroidTest` and `:macrobenchmark:connectedBenchmarkAndroidTest` on the selected phone or AVD. Connected tasks take `--serial` and `-Pandroid.testInstrumentationRunnerArguments.class=`; they do **not** accept `--tests`.

These are local console tasks, not PR gates. The UI warns that emulator + build has frozen this machine.

## Witness screenshots and visual confirmation

L1 desktop cases dump one end-state PNG each (`shared/build/l1-witness/<method>.png`) via `L1Witness.kt`. Writes are best-effort (okio; iOS swallows failures). These are **not** goldens — no byte or hash assertions (ADR-0010). Humans verify renders by viewing them.

While a run is active, L1 also writes a **live preview** and **key-node frames** to `docs/testmap/artifacts/` (gitignored; wiped when a new run starts):

- `live/preview.png` — latest frame, overwritten
- `keyframes/` — one PNG each time a product tag appears

The console Run tab polls `/api/status` and shows those files via `/artifacts/…` (`Cache-Control: no-store`). This is still not a live video of the editor — it updates when Compose idles on a tag, about once a second in the UI.

The console serves them only from that directory:

```
GET /witness/<file>.png
```

The filename is basename-only (`seamFeedShowsEditor.png`). Separators and `..` are rejected (404). Missing files are 404 with `Cache-Control: no-store`.

Edge detail shows a Screenshots section (lazy thumbs in served mode). `file://` shows a local-server hint and no confirm/run chrome.

**Confirmed** means a human viewed the latest screenshots for an edge and signed off:

- `POST /api/confirm` `{edge_id}` writes `{edge_id, run_id, confirmed_at}` into `docs/testmap/runs/confirmations.json` (gitignored, local only). Confirming again overwrites. `DELETE` or `{revoke: true}` clears one.
- `GET /api/map` includes `confirmations` so the canvas, list, and detail can badge ✓.
- A later run that covers the same edge (different `run_id`) marks the badge stale (**re-confirm**).

This is an operator aid, not a CI gate. Do not commit confirmations or witness PNGs.
