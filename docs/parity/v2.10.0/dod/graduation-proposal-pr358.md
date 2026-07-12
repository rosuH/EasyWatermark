# Graduation proposal — Draft PR #358

**PR:** https://github.com/rosuH/EasyWatermark/pull/358  
**Title:** Draft: Compose/KMP migration checkpoint, not merge-ready  
**State (2026-07-12):** OPEN · **isDraft: true**  
**Branch:** `feat/migrate_to_compose` (local typically ahead of origin)  
**Audit:** `docs/parity/v2.10.0/dod/s9-dod-audit-2026-07-12.md`  
**§9 overall:** **NOT MET** — do **not** redefine as “almost done = done.”

---

## What this PR is

An **integration checkpoint** for multi-year Compose → KMP/CMP work: shared data layer, shared CMP editor shells, Android Compose production UI, Desktop editor window, iOS CMP host + Swift edges. It is **not** automatic proof of public three-platform 1:1 ship.

## What is solid (evidence-backed)

- Android **debug + release** assemble; non-strict unit **53/0** (`build/s4d383-dod-audit/`).  
- **Strict FNV goldens** re-run on lab host: `WATERMARK_GOLDEN_STRICT=true` + `--rerun-tasks` → **53/0**, stdout `strict=true` (`build/s4d383-dod-audit-r2/`).  
- Shared multi-target compile; desktopTest **132/0**; **`iosSimulatorArm64Test` RUN 101/0** (r2).  
- Desktop headless product spine + **createDistributable** unsigned `.app` (Corretto 17).  
- Packaging workflow historically green on this PR.  
- iOS XCUITest **19/0** at A5a checkpoint (`build/s4d383-a5a-final/`).  
- Local program tickets **01–09 complete**; Android **07/08 owner-signed**; B3 exception registry published.  
- Shared CMP is product UI route of record with **documented** platform edges.

## What is still open / residual

| Residual | Severity for merge |
|----------|-------------------|
| ~~Strict FNV goldens not re-run~~ | **Closed (r2)** — local/pinned host **53/0** |
| ~~Full `iosSimulatorArm64Test` RUN~~ | **Closed (r2)** — **101/0** |
| Parity archive not exhaustive (locale/theme/recording matrix) | Medium — core screens signed |
| ~~`codex-goal-v2.md` §11 stale~~ | **Closed** — refreshed post audit + r2 |
| Unsigned Desktop only; no notarization | Expected — ADR-0013 |
| Android native vs Skiko text/icon raster split | Expected — ADR-0004 closed |
| PHPicker cell automation | Toolchain exception |
| Local branch may be **far ahead** of `origin` | Operational — push is owner-gated |

## Merge plan options (owner chooses)

### Option A — **Keep Draft; continue polish** (Recommended if archive residual matters)

1. ~~Optional: re-run strict goldens + `iosSimulatorArm64Test`~~ — **done (r2)**.  
2. Optional: expand parity archive (zh / theme / recordings) for residual inventory rows.  
3. ~~Refresh `codex-goal-v2.md` §11~~ — **done**.  
4. Push branch when owner authorizes; keep #358 Draft until owner accepts remaining residual debt or closes archive.  
5. **Do not merge** until owner re-opens graduation.

### Option B — **Ready-for-review without full §9** (integration merge to long-lived branch)

1. Owner accepts **PARTIAL** §9 with residual list as known debt (primarily archive breadth).  
2. Push `feat/migrate_to_compose` when authorized.  
3. Convert #358 from Draft → Ready for review **only if** owner wants review now.  
4. Merge only with **explicit owner merge command** (squash/rebase/merge strategy TBD by owner).  
5. Track residuals as post-merge tickets (archive expansion; notarization out of scope).

### Option C — **Not ready; no graduation**

1. Leave Draft.  
2. Open only residual work (e.g. full locale/theme parity archive).  
3. Re-audit §9 before any merge discussion.

## Explicit non-actions (unless owner overrides)

- No force-push, no merge, no rebase onto main, no deleting Draft.  
- No claim of three-platform pixel parity.  
- No claim Desktop packaging = public store ship.  
- No public GitHub migration issue ops for this program.

## Recommended owner reply format

```text
GRADUATION: A | B | C
PUSH: yes | no
MERGE: no | (only if B and explicit)
STRICT_GOLDENS: re-run | defer
```

## Commander recommendation

**Option A** if expanding the parity archive still matters before any public ship claim; **Option B** if owner accepts core-screen sign-off + documented exceptions as enough for Draft→review / integration merge discussion.  
**§9 still NOT MET** (archive breadth + intentional packaging/renderer exceptions) → **do not merge** without explicit owner command.  
Automated residual gates from the initial audit (strict goldens, `iosSimulatorArm64Test`) are **closed on this lab host**.
