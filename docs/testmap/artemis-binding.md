# Agent binding (not a second map)

`map.yaml` is the only topology (**15 nodes / 29 edges**). The **default** agent adapter is Agent Device
(`edge:<id>@android#agent`, and `@ios#agent` when that edge’s iOS drive is not `none`).
Optional `docs/testing/agent-device-cases.json` is execution material keyed 1:1 by those
same ids — not a second map. Compose Desktop is not an Agent Device target.

Artemis is **historical and deprecated** (ADR-0037 P4). `docs/testing/artemis-cases.json`
stays read-only, still 1:1 with the same 29 map edge ids. CLI still accepts `#artemis`
so old run records parse. Do not treat it as the console/select default.

- Generator and `TestMapGuardTest` fail if `artemis-cases.json` grows extra edge ids or drops a map edge. If `agent-device-cases.json` exists, the generator applies the same 1:1 check.
- Drive labels stay `real | seam | none`. An agent walking the **system picker** on
  `pick-to-editor` is recorded on the run (`honesty` in the generated console payload).
  It does not rewrite `platforms.android.drive` from `seam` to `real`.
- Replay / SDK `completed`, process exit 0, and `traces/…PASS…` are not product pass.
- Independent review (`build/artemis-suite/20260912-reviewed-results.json`) is not
  human confirmation. Only the console Confirm button writes `actor: human`, and
  it must send the `run_id` the user is looking at. New runs do not mint confirmation.
- Historical Add More failure is bound to run `20260912T140000-000c3f44` and its
  evidence directory. A new run of the same edge is not auto-failed; it stays
  pending until its own review. Original `result.json` files are not rewritten.
- Color: round-2 `result.json` stays `failed`; `file-recheck.json` is an offline audit.
- Agent Device evidence lives under gitignored `build/agent-device/`. Historical
  Artemis evidence stays under `build/artemis-suite/`.

L1 Compose sources live under `shared/src/uiTest` and are on the `:shared:desktopTest`
and iOS test (`iosArm64Test` / `iosSimulatorArm64Test`) classpaths. Wiring:
[compose-uitest-slice.md](compose-uitest-slice.md). They are not on Android host /
`commonPureTest`.
