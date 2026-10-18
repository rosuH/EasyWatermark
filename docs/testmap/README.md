# E2E test map

`map.yaml` is the single source of truth for product paths. Nodes are screens, overlays, sheets, and dialogs; editor tabs and layout classes are states of the editor node. English and Chinese titles live in `copy.yaml`, keyed by node, edge, and case ref. Do not create a second topology in the web client.

The operating contract is [historical test-map ADR](historical-adr-0032-e2e-test-map-and-harness.md). Current ADR-0032 concerns splash motion. [ADR-0037](../adr/0037-agent-device-testmap-adapter.md) defines the Agent Device adapter. These tools are local checks, not new GitHub required checks.

## Optional AI SDK hybrid

Agent Device defaults to deterministic replay/batch. For a bounded exploratory run, opt into the optional AI SDK harness:

```
npm ci
TESTMAP_AGENT_DEVICE_MODE=ai-sdk \
  scripts/e2e-run.sh edge:launch-to-about@android#agent
```

AI SDK defaults to **hybrid**: Grok plans the journey, Jev chooses controls, and a valid decision below confidence `0.9` hands back to Grok with a fresh snapshot. Grok chooses a current target for an uncertain control choice, or replans the remaining route for a low-confidence `none`. Invalid responses, high-confidence `none`, missing required state and provider errors stop the attempt. An explicitly optional incidental overlay may be skipped on a valid high-confidence `none`; skipped actions alone cannot complete a journey. Overall mode still defaults to replay.

The general hybrid controller covers every supported Android/iOS binding in `agent-device-cases.json` (25 Android + 22 iOS). It reads the existing case script, setup, verification and honesty fields as planning context; unsupported cases and Compose Desktop remain outside Agent Device. Select the original Grok ToolLoopAgent with `TESTMAP_AGENT_DEVICE_AI_CONTROLLER=grok`. `TESTMAP_AGENT_DEVICE_PILOT_ARM` remains an explicit benchmark override and takes precedence. Direct Node invocations accept `--controller hybrid|grok` and default to hybrid too.

Jev requests `jev-latest` through the TypeSafe HTTP API; traces retain the returned model version. It reads `TYPESAFE_AI_API_KEY` from the process environment or the existing private, gitignored `build/agent-device/jev-pilot.env`. The key is never passed in argv or saved in task metadata. Normal Android Testmap hybrid `pick-to-editor` runs use [`testmap_pick_export_evidence.py`](../../scripts/testmap_pick_export_evidence.py): prepare a uniquely identified synthetic image, verify the fresh app-owned published MediaStore PNG, remove only owned fixture/export entries, preserve baseline IDs, and restore/read back preference bytes. iOS uses its separate oracle below; the comparison runner retains its own preparation and restoration.

iOS `pick-to-editor` opts in through [`testmap_ios_pick_export_evidence.py`](../../scripts/testmap_ios_pick_export_evidence.py). `TESTMAP_IOS_OWNED_DEVICE` must be the absolute path to this checkout's `build/agent-device/ios-hybrid-owned-device.json`, identifying a newly created dedicated simulator (`created_for_this_run=true`, name prefix `EWM-Hybrid-Owned-`). The CLI UDID, ownership metadata and preparation state must agree. Preparation requires an empty active Photos library and imports only the synthetic fixture. `TESTMAP_IOS_PICKER_STATE` references the successful `pick-export/prepare.json`; the visual bridge requires `prepared=true`, an empty baseline and the unchanged source hash. Missing ownership metadata or skipping owned setup rejects this hybrid path before seeding or running the controller. Existing personal libraries are outside this opt-in.

Maintainers must pre-provision the new dedicated simulator, clean Debug app installation and ownership metadata; there is no repository provisioning CLI. Register only a simulator created for this run: never change metadata to claim a personal or preexisting simulator. Confirm the empty active library and the PhotoKit helper's real full read/write UI authorization; a `simctl` TCC grant alone is not sufficient evidence. For `simctl clone` environments, confirm app installation/registration and that the product/helper data containers resolve inside the new simulator. The recorded run and seed instances were destroyed; their UDIDs and old metadata cannot be reused.

With those prerequisites satisfied, run the existing entry point from the repository root, with any benchmark pilot override unset:

```bash
TESTMAP_AGENT_DEVICE_MODE=ai-sdk \
TESTMAP_AGENT_DEVICE_AI_CONTROLLER=hybrid \
TESTMAP_AGENT_DEVICE_TIMEOUT_MS=1800000 \
TESTMAP_IOS_OWNED_DEVICE="$PWD/build/agent-device/ios-hybrid-owned-device.json" \
  scripts/e2e-run.sh --device '<new-owned-udid>' edge:pick-to-editor@ios#agent
```

The screenshot-based iOS controller can exceed the global default of `360000` ms (6 minutes). This example sets a per-run timeout of `1800000` ms (30 minutes); adjust `TESTMAP_AGENT_DEVICE_TIMEOUT_MS` as needed. The global default remains unchanged.

This runs verification, not one-command provisioning. The runner supplies `TESTMAP_IOS_PICKER_STATE` after `prepare()` succeeds. The evidence module's only standalone CLI is `--self-check`; it does not prepare a device. Cleanup destroys this dedicated instance, so every later run needs a newly provisioned instance and matching metadata.

When native AX omits the real PHPicker grid, [`testmap_ios_picker.mjs`](../../scripts/testmap_ios_picker.mjs) asks Grok to describe screenshot controls with the owned fixture as a reference. Jev chooses candidates; the existing low-confidence Grok handoff/replan receives current visual context. Visual `selected` stays unknown. A visual click must rebind to the unchanged cropped-image hash; this bridge does not inject a product fixture or bypass PHPicker. Native permission alerts and loading states stay on the AX path. The product retains add-only Photos access; the isolated PhotoKit oracle needs full read/write access to the owned synthetic library.

The file oracle requires exactly one new original Photos PNG resource matching the fresh app `ewm_out_*` hash, independently decodes 960×640 pixels, checks retained source bands and changed pixels, and checks persisted marker bytes. These checks do not prove the exact rendered text. Cleanup restores and verifies both DataStores and the app preference plist, revalidates active asset IDs/resource hashes, then destroys only the ownership-checked dedicated simulator and its data directory, including Recently Deleted. Unknown assets or failed restoration block destruction. This opt-in does not establish ordinary iOS Simulator or physical-device acceptance.

The generic controller keeps a route overview and plans 2–12 steps per batch, up to 80 steps overall. Later batches receive the latest observed UI and completed steps. Jev chooses clicks, input fields, scroll/gesture surfaces and slider tracks. Actions rebind uniquely against a fresh full snapshot and pin the current ref generation. Back, keyboard, waits and assertions remain host operations. Target-relative gestures use current geometry rather than recorded screen coordinates. A `remember` step reads an original field value for later restoration; saved originals cannot be overwritten. Native state is retained as unknown when absent; an explicit selected/unselected assertion must be backed by a native fact. Switch values can be checked independently; `selected` is not `checked`.

Plans must end with concrete observations. Conditions support exact native `label` matching separately from label/value `text` searches; unknown condition fields are rejected. Negative assertions reject incomplete, stale or system-occluded snapshots. Export paths additionally require this run to dispatch an export and observe the required success/failure counts. Failure recovery uses a fixed host-owned source restoration operation after observing this run's failed export; neither a model command nor post-exit native replay performs the repair. Recovery state is invalidated during cleanup. Original case support and `drive` limits still apply, including iOS PHPicker visibility limitations. Routing coverage is not device acceptance for all 47 bindings.

`hybrid-trace.json` records the plan, model answers, confidence, handoffs, refreshed targets, native state, actions and checks; raw snapshots remain alongside it. Existing bounded benchmark pilots remain separate, and their prior timing results do not establish generic-controller performance.

The harness uses the local Magpie endpoint `http://127.0.0.1:3425/v1` and `cursor/grok-4.7@500k` by default. Override them with `MAGPIE_BASE_URL`, `MAGPIE_API_KEY`, and `TESTMAP_AGENT_DEVICE_AI_MODEL`. AI mode is exploratory, remains outside PR CI, and writes `agent-trace.json`, `post-snapshot.json`, screenshots, and `result.json` under `build/agent-device/`. A completed model loop is still at most `executed_review_required`; it is not a product pass.

The Android-only `launch-to-about` controller pilot compares Jev and Grok with the same two-phase host, native action-node candidates (with ancestor IDs and sibling labels), native `selected=false` check, and ordered Launch → About → Launch oracle. It compares target selection, not the unrestricted Grok agent. Put `TYPESAFE_AI_API_KEY=...` in a private, gitignored env file, then run:

```bash
python3 scripts/testmap_compare_launch_about.py --serial emulator-5554 \
  --env-file build/agent-device/jev-pilot.env --repeats 3
```

The comparison alternates controller order and writes the plan before running. Each attempt remains a normal testmap record. `report.md`, raw per-attempt results, provider timings, and controller timings live under `build/agent-device/launch-to-about-pilot-<timestamp>/`. Jev calls the [official TypeSafe HTTP API](https://docs.typesafe.ai/api) directly; no AI SDK upgrade is needed. Its Choice must name a current candidate, have a valid distribution, and meet the fixed `confidence >= 0.9` gate. Low confidence, missing state, or an ambiguous target stops the attempt. The threshold is a pilot setting, not a calibrated reliability guarantee. `selected=false` on a navigation button does not establish checkbox checked-state behavior. `npm run testmap:agent-ai:check` exercises the gate and verifies blocked decisions never click, without network/device access.

The optional `hybrid` pilot gives Grok the whole roundtrip goal and initial snapshot. Grok returns a plan of 2–4 navigation steps; Jev chooses a control for each instruction. Only a valid, known, non-`none` Jev choice below `0.9` hands control back to Grok. Handoff includes a newly captured snapshot, current candidates, the plan, completed instructions, and Jev's answer. Grok can choose a current target or stop; its choice must pass the same refreshed-target and native `selected=false` guards. Invalid responses, missing state and provider errors stop the run. The host verifies actual About visitation and return to Launch.

```bash
python3 scripts/testmap_compare_launch_about.py --serial emulator-5554 \
  --env-file build/agent-device/jev-pilot.env --arms hybrid --repeats 1
```

For branch verification, add `--force-handoff-step 1` or `2`. This hybrid-only test fault lowers the **effective** confidence to `0.89` after validating the raw Jev response. The trace preserves the original answer, marks the injected gate answer and records Grok's handoff decision. Injected outcomes are branch tests, not evidence of natural Jev uncertainty. Planner and handoff request timings are included in total provider time; their models, usage, actual click targets and snapshots stay in `pilot-trace.json`.

For the Android pick → edit → export comparison:

```bash
python3 scripts/testmap_compare_launch_about.py --serial emulator-5554 \
  --edge pick-to-editor --arms hybrid grok --repeats 3
```

Both arms receive a fresh Grok whole-task plan. Pure Grok chooses every control; hybrid uses Jev and hands only valid low-confidence choices to Grok. Both use the same bounded click/fill executor, fresh pinned refs, explicit unselected checks, and PNG configuration evidence. For installed builds that omit radio selection, the JPEG-only quality control must be disabled; independent file decoding still requires a PNG signature. An already-selected Content tab or PNG option is skipped. No actions follow export dispatch.

Preparation backs up both watermark DataStores and creates one synthetic image. A globally unique MediaStore timestamp identifies its exact accessibility label in the real system picker; selection does not depend on recency. The controller verifies text field readback, confirmation and exact export counts. An independent oracle requires a fresh app-owned, published PNG, decodes its 960×640 pixels, checks retained source color bands, changed pixels and persisted text. This does not prove the exact rendered text; OCR and visual review supplement it. Only owned test files/exports are removed, and original preferences are restored and checked after each attempt.

Reports and preregistered order/source hashes are under `build/agent-device/pick-to-editor-pilot-<timestamp>/`. Successful-attempt task medians include planning, selection, execution, evidence capture and session lifecycle; setup, external file checks and restoration are separate. Provider time includes the planner and handoffs. Failed or incomplete attempts never count as speed improvements. This compares two bounded planned controllers, not an unrestricted ToolLoopAgent. The existing testmap edge and its `drive` classification stay unchanged; this remains exploratory and outside CI.

## Start and run

From the repository root, with Python 3:

```sh
scripts/e2e-console.sh
scripts/e2e-run.sh --list
scripts/e2e-run.sh edge:launch-to-about@android#agent
scripts/e2e-select.sh origin/master...HEAD
scripts/e2e-verify.sh --change 'Describe the change' --range origin/master...HEAD --run --android-device '<authorized-serial>' --ios-device '<authorized-udid>'
```

Open the local console at `http://127.0.0.1:8931`. The console serves the built client from `tests/testmap/web/dist/index.html`; running it does not require Node. See the [web client guide](../../tests/testmap/web/README.md) to change or build the client. The web source, dependencies, lockfile, and build output stay inside that directory; project data stays here.

The CLI is the only runner. The web client can select tasks, start a CLI run, stop it, and display its records. Looking at a page must not start a test or boot a device. There is no queue-pause control. Stop is complete only when the recorded process and task state say it is complete.

Mobile Agent Device tasks can expand to supported Android and iOS lanes. Review the actual task list and selected devices before starting. Desktop remains host/L1 coverage; a witness image is not Desktop device video. Do not close existing emulators, clear user data, or open native scrcpy windows automatically. Warn before sustained emulator and build load; cap Gradle with `--max-workers=8`.

The Android E2E runner exposes `android-ai-journey` and the diagnostic `android-large-image-preview` task. Each must run alone, once, on an explicitly selected Android device. The preview task creates one owned 49,861×1,350 banded PNG, then checks fresh same-process decode and Canvas-fatal logs; `bug_reproduced` is separate from the native test failure. It does not claim an export, and fixture/private-state restoration remains required.

### Preparation and recovery

Preparation backs up the preference files it changes before resetting them. The private recovery journal stays inside the task's evidence directory; a device lock under `build/testmap/setup-backups/` points to it. Normal completion, failure, and Stop attempt restoration. A restore failure fails the task, retains the backup, and blocks another setup on that device in this checkout. A hard kill or device loss can prevent immediate restoration.

If recovery is required, inspect the recorded backup and account for any settings changed since the interruption, then run `python3 scripts/testmap_setup.py --restore-reviewed-backup /absolute/path/to/setup-backup.json`. Recovery is explicit so an old backup cannot silently overwrite newer work. Keep recovery journals local; they contain app settings. Coordinate device ownership across checkouts.

Android CLAMP and filmstrip walks start at Launch and select A+B once; Add More would append to a preloaded selection. The Android in-app-gallery walk journals its media-read permission baseline, temporarily grants access only when needed, and restores the original grant and flags. Existing grants are preserved; a denied user choice or partial grant is not overridden. The iOS gesture scripts use the dedicated QA simulator’s 402×874 logical-point viewport; other layouts need fresh AX geometry.

This protects the harness's preference, crash-state, display, and synthetic-fixture changes. It is not a full app snapshot: exports, templates, other script actions, and the previous in-memory navigation session are outside that transaction. iOS preparation preserves temporary files, saved application state, and Photos permissions. The Library Read upsell case reports an unmet precondition instead of changing authorization it cannot safely restore.

## Use the console

- **Catalog:** Map and Tree share filters and selected paths. Hover is temporary; clicking fixes the selection. Edge detail shows coverage, evidence, and copyable refs. Technical fields can be expanded without making them the primary navigation.
- **Execution:** The task list contains one row per path, platform, and repetition. Each mobile lane has its own device frame and steps. Device proportions are preserved. Selecting a recorded step shows that run's screenshot, not the current device frame. Host tasks, device selection, logs, and L1 evidence remain accessible.
- **History:** Open a fixed run to inspect its source, commit, package identity, results, durations, and evidence. Missing screenshots and capture errors are shown explicitly. Historical evidence must not be silently replaced with current live frames.
- **Confirmation:** The edge detail binds confirmation to the displayed covering run. Only a user may confirm or revoke. A later covering run can make an earlier confirmation stale.

The page polls the server and reconnects after a connection loss. Hidden pages release polling and video resources. Live mobile video can fall back to still frames when the video transport is unavailable. Missing or failed capture is not a successful test result.

## Result and coverage vocabulary

Automatic execution, script checks, agent observation, independent review, human confirmation, and product outcome are separate evidence layers. `review_required` means replay finished and still needs review; it is not product acceptance. Process exit zero, a completed agent run, or a green build cannot replace visual inspection or Human Confirm.

Each edge declares per-platform drive coverage:

| Drive | Meaning |
|---|---|
| `real` | The script operates the app's own control. |
| `seam` | The check bypasses a system boundary, for example fixture input or an injected path. |
| `none` | No script reaches this platform path. |

Walking a system photo picker, native file dialog, share sheet, or permission prompt does not turn a seam into real coverage. The Android share walk delays capture by 500 ms after Share so the native panel can be reviewed; that delay does not assert chooser success or identify the shared file. Unsupported paths stay visible as gaps. Failed, timed-out, interrupted, stopped, or unknown results cannot be made successful by filtering or missing evidence. Diagnose replay/setup failures before deciding whether their cause is the environment, the script, or the product.

Artemis is deprecated historical provenance; [bindings](artemis-binding.md) and historical evidence remain readable. Do not introduce another execution engine.

## Records and API

The runner atomically writes `runs/<id>.json` before execution and after progress changes. Step images live in `runs/<id>/steps/`, with `shot_error` when capture failed. New runs insert SDK screenshot actions between original actions and retain the original script hash, derived script, and exact step mapping. `shot_capture` identifies a successful capture after that step and before the next action; older images without it are labelled as timing unverified. Failed actions and session close do not borrow a later frame. The SDK plan digest is recorded only when the SDK reports one.

The record includes source (`manual`, `select`, or `verify`), script commit/dirty state, package identity, device, repeated task rows, timing, and outcomes. Records and filled reports are gitignored local evidence, not product source.

| Endpoint | Role |
|---|---|
| `GET /api/catalog` | Static catalog data and human titles from the existing map; no baked SVG layout. |
| `GET /api/map` | Latest edge results, covering run IDs (`result_runs`), confirmations, and witnesses. |
| `GET /api/tasks`, `/api/devices` | Available checks and device choices. |
| `GET /api/status`, `/api/status?id=<run>` | Current or explicitly selected run state. |
| `GET /api/runs`, `/api/runs/<id>` | Run history and one immutable selection of evidence. |
| `GET /api/runs/<id>/steps/<shot>` | A screenshot from the selected run. |
| `POST /api/run` | Starts the CLI with `tasks`, `device`, `repeat`, and `source`. |
| `POST /api/stop` | Requests stop for the recorded run; errors remain visible. |

The backend owns validation. A busy runner returns 409; a forbidden stop returns 403. Frontend types must follow actual API responses, not the old P0 draft's guessed fields.

### Human Confirm

Only the user's page interaction may call `/api/confirm`. Agents must not call it via a browser, HTTP tool, script, or self-check. Automated tests use mocks or internal validation functions and must not create real confirmations.

- Confirm uses `POST {token, edge_id, run_id}`. The client submits the run ID already displayed to the user; it must not silently switch to another run at click time.
- The run must have ended. Every counted task covering the edge must be `passed` or `review_required`; failed, running, paused, pending, interrupted, stopped, uncovered, and unknown results are rejected. A stopped task that never started does not count as coverage.
- Revoke uses `POST {token, edge_id, revoke: true}` or `DELETE {token, edge_id}`. The UI asks for a second click before revocation. Undo after confirmation restores the prior run when there was a prior confirmation.
- The page supplies `<meta name="ewm-confirm-token">`. Tokens expire after 12 hours; multiple pages remain valid, up to 64 tokens. A 403 asks the user to refresh; a 400 shows the validation reason. Do not retry confirmation automatically.
- `result_runs` maps each edge to its newest covering result. It is not the singular `latest_run`. A newer covering run makes an older confirmation stale; a host-only run without edge results does not. Show missing platforms explicitly.

## Checks and release evidence

```sh
python3 scripts/generate_testmap.py
python3 scripts/testmap_run.py --self-check
./gradlew --max-workers=8 :shared:desktopTest
```

The generator validates the map and titles and produces `map.mmd` and `coverage.md`. `TestMapGuardTest` checks map/code references. L1 witnesses show observable end states, not byte-comparison goldens. Review them visually. Preserve the `screens_ref` links to the [frozen parity inventory](../parity/v2.10.0/inventory/screens.md).

For product changes, follow [Mode B](../../eval/README.md): select affected paths, run the selected host checks, diagnose failures with bounded single cases, complete the required repetitions, then fill the local verify report with observations and limitations. Human Confirm remains pending until the user acts. Do not recommend merge or ship without the required evidence and confirmation.

The canonical skill is [`skills/e2e-testmap/SKILL.md`](../../skills/e2e-testmap/SKILL.md); `.agents` and `.claude` link to it. For an unscripted path, use [explore and promote](../agents/e2e-walk.md) instead of inventing a parallel map.
