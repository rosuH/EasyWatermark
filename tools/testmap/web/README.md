# Testmap web client

The Python console serves `dist/index.html`. Node is needed only when changing this client. The client is self-contained: project topology, names, cases, devices and evidence come from the console APIs. No imports or workspace configuration outside this directory are required.

## Develop and verify

Use Node 22.12+ (Node 24 recommended) and the pinned pnpm version in `package.json`.

```sh
cd tools/testmap/web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm check:dist
```

`check:dist` rebuilds and compares the committed single-file artifact byte for byte. Run `build` after a source edit, then commit `dist/index.html` with that source. Only this file under `dist/` belongs in Git. Dependencies stay in this directory and are locked by `pnpm-lock.yaml`.

`pnpm dev` starts a loopback-only Vite server. It proxies APIs and evidence to the local console on port 8931; edit the target in `vite.config.ts` if your console uses another port. The development page has no human-confirmation token. Use the page served by the Python console to confirm a run; do not copy tokens into development fixtures.

## Interface

- **Catalog:** map and tree share filters and the pinned path. Hover previews a path. Click pins its details, cases, references, draft task selectors and confirmation record. L1 reference witnesses are labelled separately from run evidence.
- **Run settings:** select checks or device walks, repeat count, a specific device, and host/diagnostic tasks. Auto-device agent walks may expand across supported mobile platforms. The resulting execution queue is the authoritative expanded plan.
- **Execution:** each device lane follows its own task and steps. Clicking a step pins that step's recorded screenshot; new live frames cannot replace it. Return to live explicitly. The task queue wraps long names and is not truncated. Logs, commands, package identity, result layers and screenshots remain inspectable below.
- **History:** opens a run by its exact id. Historic runs never start live media. Shared current L1 previews are not shown as historic evidence.
- **Human confirmation:** the shown latest covering run is captured by the click handler. Failed edge tasks, incomplete runs and missing edge results cannot be confirmed. A failed sibling edge does not invalidate a passing edge. Revoke requires two clicks. The five-second Undo restores the previous run id, if any. HTTP 403 asks for a page refresh and never auto-retries; HTTP 400 displays the server's reason.
- **Visibility:** hiding the browser tab aborts requests and media. Leaving Execution releases the device stream and decoder. Video falls back to real still frames; missing or disconnected frames are shown explicitly.

The small graph uses a stable four-column layout based on server ordering, with an id fallback for unknown nodes. Large or dense catalogs may need a dedicated graph-layout engine. Filter selection never changes the existing run draft.

## Files and contracts

- `src/api-types.ts`, `model.ts`, `api.ts`: API shapes, shared selection rules, abortable polling.
- `src/App.tsx`, `Catalog.tsx`, `Run.tsx`, `Confirm.tsx`: the workflow and its views.
- `src/media.ts`: existing length-prefixed H.264 / Annex-B protocol and still fallback.
- `src/components/ui/button.tsx`: local shadcn/ui Button pattern, using Radix Slot, CVA and Tailwind utilities.
- `src/style.css`: responsive layout, focus styles and 120/200/320ms motion tokens. Frequent selection and media do not animate. Reduced motion is honored.
- `test/behavior.test.tsx`: isolated mock checks for confirmation, undo/revoke, missing runs, failed edge results, pinned evidence, visibility cleanup, stale responses, filtering and packet framing. Tests stub all requests and never contact a real confirmation endpoint.
- `vite.config.ts`: React, Tailwind and single-file production build.
- `scripts/check-dist.mjs`: artifact consistency check.

The client consumes `/api/catalog`, `/api/map` (`result_runs`, not an invented alias), `/api/tasks`, `/api/devices`, `/api/status`, `/api/runs`, and existing media/evidence endpoints. It does not replace the Python runner. Browser mock checks do not establish device behavior or human acceptance; verify those separately on the same integrated build.

Step images with `shot_capture.method = agent-device-inline` and `timing = after-step-before-next-action` come from an SDK screenshot action inserted after the original step. The capture completes before the next scripted action; this does not guarantee a settled UI. Older images stay readable with a timing-unverified notice. A failed or interrupted screenshot never borrows a later live frame. The run retains its derived script and source-to-replay mapping; SDK resume digests belong to that derived plan.

The offline build retains dependency and source-pattern licenses in a collapsed “Third-party licenses / 第三方许可” section. Run `pnpm check:licenses` to check the single HTML and original runtime license texts; `check:dist` also runs this check. See [notice sources](licenses/README.md).
