# EasyWatermark Android journey runner

This private runner uses `e2e@0.19.0`, `@e2e-dev/mobile@0.11.0`, and the mobile package's isolated `agent-device@0.21.22` dependency. It does not change the global `agent-device` CLI pin. The model connects through Magpie's local OpenAI Chat Completions endpoint using `cursor/auto` and a documented dummy key; it does not read Pi credentials.

The backend prepares the synthetic fixture and backup first, then runs from this directory with a new run ID and output directory:

```sh
E2E_TELEMETRY_DISABLED=1 \
EWM_E2E_DEVICE=emulator-5554 \
EWM_E2E_RUN_ID=<testmap-run-id> \
EWM_E2E_OUTPUT=.runs/<testmap-run-id> \
EWM_E2E_SETUP_STATE=<sanitized-setup-state.json> \
EWM_E2E_FIXTURE_NAME=<fixture-basename> \
EWM_E2E_EXPECT_TEXT=<unique-watermark-text> \
EWM_E2E_BASE_URL=http://127.0.0.1:3425/v1 \
EWM_E2E_MODEL=cursor/auto \
npm run android:journey -- --no-cache
```

Each output directory is write-once. The wrapper forces `--no-cache`, telemetry off, one Android target, one test, and a unique Agent Device state directory at `.e2e/agent-device/<run-id>`. It writes a normalized `testmap-result.json` beside the native `report.json`, trace, and PNG artifacts. The backend must still verify the selected MediaStore row, exported bytes, watermark state, and restoration independently.

This first live proof keeps the replay cache disabled. It verifies fresh model-directed actions and the native report; cached replay behavior is not part of this run. The report includes separate step-associated screenshots after the edit assertion and after export.

## Large-image preview failure reproduction

The separate `android-large-image-preview` scenario exercises one system-picker selection and preview attempt for the run-owned `49861 × 1350` PNG. It uses the same CLI and output contract, but does not need watermark text in the manifest:

```sh
E2E_TELEMETRY_DISABLED=1 \
EWM_E2E_SCENARIO=android-large-image-preview \
EWM_E2E_DEVICE=emulator-5554 \
EWM_E2E_RUN_ID=<testmap-run-id> \
EWM_E2E_OUTPUT=.runs/<testmap-run-id> \
EWM_E2E_SETUP_STATE=<sanitized-setup-state.json> \
EWM_E2E_FIXTURE_NAME=<fixture-basename> \
EWM_E2E_BASE_URL=http://127.0.0.1:3425/v1 \
EWM_E2E_MODEL=cursor/auto \
npm run android:journey -- --no-cache
```

This case runs one `app.open` and one bounded live `agent.act` (up to 8 model calls / 180 seconds). It stops at the preview and does not edit or export. A started native failure remains `failed`; incomplete model evidence is marked `partial`. The backend separately checks the fresh app process and crash logs to classify product bug reproduction.
