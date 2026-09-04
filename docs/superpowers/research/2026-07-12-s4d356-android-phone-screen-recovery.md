# S4d-356 — Android Phone Screen recovery / visual backfill (docs only)

**Date:** 2026-07-12
**Type:** environment evidence — **no product code**
**Question:** Recover a non-stale AndroMeld Phone Screen and complete deferred visual gates for S4d-348 About and S4d-350 SaveExport?

**Verdict: BLOCKED (external mirror / MCP control).** About and SaveExport visual gates remain **NOT VERIFIED**. No parity claim.

---

## 1. Pre-recovery session state (Codex-reviewed)

| Item | Value |
|---|---|
| Device | `emulator-5554` |
| Phone Screen session | `CE8439F0-97F7-4474-889F-1FF0FE289499` |
| Initial MCP control | Available on that existing session |
| Screenshot frame | **38** |
| Content | **Launcher** (not debug app UI) |
| SHA-256 | `15efa7d59ea1708d032e1c94ca6a3b2f9c49a2a050bae674ef67bcbcc3ffc273` |
| After debug app launch | Frame **stale / identical** to launcher despite foreground metadata |

Install of current HEAD debug APK was already done in S4d-354 (`./gradlew :app:installDebug`; no adb UI). S4d-356 did **not** re-verify `READ_MEDIA_IMAGES`.

---

## 2. Allowed recovery attempt (Codex)

- Codex attempted the allowed **`session.start`** recovery path **without** stopping the emulator.
- **Provenance:** `session.start` was a **direct live Codex MCP invocation** recorded in the task trace; **no** successful session-start payload and **no** build artifact from that call were produced. The **observable follow-up** was `sessions.list` failing with control-socket refusal.
- Immediately subsequent **`sessions.list`** failed:

  ```text
  Could not reach AndroMeld MCP control socket
  .../group.com.catchingnow.andfiles.shared/am-mcp.sock: connection refused
  ```

- **Do not claim** that `session.start` **caused** the socket refusal, and **do not claim** session-start **success** — only that control was unreachable for the follow-up `sessions.list`.

---

## 3. Herdr / agent tool surface

- In the Herdr agent session used for the recovery worker brief, **AndroMeld MCP tools were not exposed** (`search_tool` returned no `andromeld.*`; doctor later showed socket unreachable from sidecar).
- That agent performed **zero UI actions** (no launch, tree, screenshot, About, or SaveExport navigation).
- This is a **tool-surface / control-path limitation**, not product or UI regression evidence.

Local logs from that attempt (optional artifacts only):

- `build/s4d356-logs/andromeld-doctor.json` — `socketReachable: false`, connection refused
- `build/s4d356-logs/s4d356-block-report.txt`

---

## 4. Explicit non-claims

| Claim | Status |
|---|---|
| S4d-348 About visual gate | **NOT VERIFIED** |
| S4d-350 SaveExport visual gate | **NOT VERIFIED** |
| Android v2.10.0 parity | **Not claimed** |
| `READ_MEDIA_IMAGES` rechecked in S4d-356 | **Not done — do not write as done** |
| Frame 38 / hash as app UI proof | **Invalid** (launcher / stale) |

---

## 5. Next recovery procedure (only when control socket returns)

1. `devices.list`
2. `sessions.list` / `session.start` if needed (do **not** stop emulator)
3. Establish a **fresh frame that visibly changes** after a visible action
4. Launch `me.rosuh.easywatermark.debug`; require **UI tree + viewed screenshot agreement** (app, not launcher)
5. Permission check (`READ_MEDIA_IMAGES`) before editor as applicable
6. Limited About + SaveExport visual backfill only when real app state permits
7. **No raw adb** UI substitute; **no emulator shutdown**

Stop after at most two legitimate session recovery attempts if mirror stays stale/inconsistent; report device/session/frame/hash evidence.

---

## 6. Closeout

| Item | Result |
|---|---|
| Product code / deps / build | None |
| Protected `2026-07-11-project-branch-goals-progress.md` | Not staged |
| Parallel owner gate | S4d-353 **A/B/C/defer** still open |
