# E2E test map

`map.yaml` is the single source of truth for product paths. Nodes are screens, overlays, sheets, and dialogs; editor tabs and layout classes are states of the editor node. English and Chinese titles live in `copy.yaml`, keyed by node, edge, and case ref. Do not create a second topology in the web client.

The operating contract is [historical test-map ADR](historical-adr-0032-e2e-test-map-and-harness.md). Current ADR-0032 concerns splash motion. [ADR-0037](../adr/0037-agent-device-testmap-adapter.md) defines the Agent Device adapter. These tools are local checks, not new GitHub required checks.

## Start and run

From the repository root, with Python 3:

```sh
scripts/e2e-console.sh
scripts/e2e-run.sh --list
scripts/e2e-run.sh edge:launch-to-about@android#agent
scripts/e2e-select.sh origin/master...HEAD
scripts/e2e-verify.sh --change 'Describe the change' --range origin/master...HEAD --run
```

Open the local console at `http://127.0.0.1:8931`. The console serves the built client from `tools/testmap/web/dist/index.html`; running it does not require Node. See the [web client guide](../../tools/testmap/web/README.md) to change or build the client. The web source, dependencies, lockfile, and build output stay inside that directory; project data stays here.

The CLI is the only runner. The web client can select tasks, start a CLI run, stop it, and display its records. Looking at a page must not start a test or boot a device. There is no queue-pause control. Stop is complete only when the recorded process and task state say it is complete.

Mobile Agent Device tasks can expand to supported Android and iOS lanes. Review the actual task list and selected devices before starting. Desktop remains host/L1 coverage; a witness image is not Desktop device video. Do not close existing emulators, clear user data, or open native scrcpy windows automatically. Warn before sustained emulator and build load; cap Gradle with `--max-workers=8`.

### Preparation and recovery

Preparation backs up the preference files it changes before resetting them. The private recovery journal stays inside the task's evidence directory; a device lock under `build/testmap/setup-backups/` points to it. Normal completion, failure, and Stop attempt restoration. A restore failure fails the task, retains the backup, and blocks another setup on that device in this checkout. A hard kill or device loss can prevent immediate restoration.

If recovery is required, inspect the recorded backup and account for any settings changed since the interruption, then run `python3 scripts/testmap_setup.py --restore-reviewed-backup /absolute/path/to/setup-backup.json`. Recovery is explicit so an old backup cannot silently overwrite newer work. Keep recovery journals local; they contain app settings. Coordinate device ownership across checkouts.

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

Walking a system photo picker, native file dialog, share sheet, or permission prompt does not turn a seam into real coverage. Unsupported paths stay visible as gaps. Failed, timed-out, interrupted, stopped, or unknown results cannot be made successful by filtering or missing evidence. Diagnose replay/setup failures before deciding whether their cause is the environment, the script, or the product.

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
