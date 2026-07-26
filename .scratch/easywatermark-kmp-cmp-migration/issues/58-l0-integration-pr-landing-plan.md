# 58 — L0 integration / PR landing plan

**What to build:** Reconstruct the unpublished product/test/CI range after remote base `8aa588d4` without replaying `.scratch/easywatermark-kmp-cmp-migration` evidence/history; land a sanitized candidate suitable for PR #358 history replacement once integration gates and owner publication steps complete.

**Blocked by:** none for reconstruction. Publication waits on coordinator commits + **ordinary fast-forward push** only when `origin/feat/migrate_to_compose` still equals `8aa588d4`.

**Status:** `READY_FOR_PUBLICATION` — local integration accepted; ordinary fast-forward preflight is next.

## Scope split

| Layer | Owner | Notes |
|---|---|---|
| Candidate reconstruction + L0 docs/script | ACSP worker | Isolated worktree only |
| Integration-test fixes (wiring guard + FileProvider isolation) | ACSP worker (owner-authorized attempt 3) | Test-only; no product behavior change |
| Final commits | Coordinator | (1) test-only commit (2) required docs(l0) commit |
| Push / PR #358 update | Coordinator after acceptance | **Ordinary fast-forward only**; never force / force-with-lease |
| Merge / tag / release | Owner | Forbidden to worker |

Worker no-push is a **worker boundary**, not the overall L0 product scope.

## Replay algorithm

1. Verify isolated worktree at base `8aa588d471d938b2602ddb5a92e6da7b84f72e32` on `codex/l0-pr358-sanitized`; clean index; no merges in `8aa588d4..dc9ecf34` (107 commits).
2. For each commit in original order (`git rev-list --reverse base..source`):
   - Diff with path filter: exclude `.scratch/easywatermark-kmp-cmp-migration/**`.
   - If empty after filter → skip (filtered-empty).
   - Else apply binary-safe patch to index, commit with original author, author date, committer, committer date, and full message.
3. On any apply conflict → **stop blocked** with exact original SHA and paths; do not invent product semantics.
4. Confirm reconstructed non-scratch tree equals `dc9ecf34` **before** L0 adjustments.
5. L0 adjustments only: three tracker files + five allowlist paths + authorized test-only fixes.

## Allowlists

### Tracker tip (exactly three)

1. `13-post-audit-correctness-quality-roadmap.md`
2. `13-roadmap-status.md`
3. `58-l0-integration-pr-landing-plan.md` (**this file**)

### Five-path product/doc allowlist (may differ from `dc9ecf34`)

1. `AGENTS.md`
2. `codex-goal-v2.md`
3. `docs/agents/issue-tracker.md`
4. `docs/migration-log.md`
5. `scripts/verify-ownership-fitness.sh`

### Authorized test-only paths (attempt 3)

1. `shared/src/desktopTest/kotlin/me/rosuh/easywatermark/session/DesktopImportExportSemanticsTest.kt`
2. `app/src/test/java/me/rosuh/easywatermark/platform/AndroidShareStagingTest.kt`
3. `app/src/test/java/me/rosuh/easywatermark/platform/AndroidIconPersistenceTest.kt` (only if required for FileProvider isolation)

### Must retain unchanged vs `dc9ecf34`

- `shared/schemas/me.rosuh.easywatermark.data.db.AppDatabase/1.json` (Room v1; `exportSchema=true`)
- `macrobenchmark/src/main/assets/bench_fixture.png`

### Forbidden in tip / reconstructed range

`spec.md`, issues `01`–`12` / `14`–`57`, `evidence/**`, result/verification/diagnosis dumps, screenshots, logs, matrices, benchmark result JSON, symbol baselines, xcresult/bin archives.

## History / object hygiene (fail-closed)

```bash
set -o pipefail
range_objects="$(mktemp)"
range_patch="$(mktemp)"
trap 'rm -f "$range_objects" "$range_patch"' EXIT

git rev-list --objects 8aa588d4..HEAD >"$range_objects" ||
  { echo "FAIL: cannot enumerate range objects" >&2; exit 1; }
git log --format= --binary -p 8aa588d4..HEAD >"$range_patch" ||
  { echo "FAIL: cannot inspect range patches" >&2; exit 1; }

if grep -E '(^|/)(evidence/|issues/(0[1-9]|1[0-2]|1[4-9]|[2-5][0-7])-|spec\.md$|stage-k|[^/]*\.(log|bin)$|[^/]*\.xcresult(/|$))' \
  "$range_objects"; then
  echo "FAIL: forbidden tracker/evidence object in reconstructed range" >&2
  exit 1
fi
local_root='/Users/'"rosu"
private_tmp='/private/'"tmp"
derived_data='Derived'"Data"
github_token='github_'"pat_"
if grep -E "${local_root}|${private_tmp}|${derived_data}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|${github_token}" \
  "$range_patch"; then
  echo "FAIL: local evidence path or credential material in reconstructed range" >&2
  exit 1
fi

new_images_json="$(git diff --diff-filter=A --name-only 8aa588d4..HEAD |
  grep -E '\.(png|jpe?g|json)$' || test "$?" = 1)" ||
  { echo "FAIL: cannot enumerate new images/JSON" >&2; exit 1; }
unexpected_assets="$(printf '%s\n' "$new_images_json" |
  grep -Ev '^(macrobenchmark/src/main/assets/bench_fixture\.png|shared/schemas/me\.rosuh\.easywatermark\.data\.db\.AppDatabase/1\.json)?$' ||
  test "$?" = 1)" ||
  { echo "FAIL: cannot check image/JSON allowlist" >&2; exit 1; }
if [[ -n "$unexpected_assets" ]]; then
  printf '%s\n' "$unexpected_assets" >&2
  echo "FAIL: unexpected image/JSON artifact" >&2
  exit 1
fi

# Tip tracker contains exactly the three lightweight files.
test "$(find .scratch/easywatermark-kmp-cmp-migration -type f | wc -l | tr -d ' ')" = "3"
```

## Tree-equivalence commands

```bash
SRC=dc9ecf344ddff02aba0e0efddb5d15a55af01c60
# After L0 allowlist + authorized tests: only allowlist/scratch/test paths may differ
git diff --name-status "$SRC" HEAD -- . \
  ':(exclude).scratch/easywatermark-kmp-cmp-migration' \
  ':(exclude)AGENTS.md' ':(exclude)codex-goal-v2.md' \
  ':(exclude)docs/agents/issue-tracker.md' \
  ':(exclude)docs/migration-log.md' \
  ':(exclude)scripts/verify-ownership-fitness.sh' \
  ':(exclude)shared/src/desktopTest/kotlin/me/rosuh/easywatermark/session/DesktopImportExportSemanticsTest.kt' \
  ':(exclude)app/src/test/java/me/rosuh/easywatermark/platform/AndroidShareStagingTest.kt' \
  ':(exclude)app/src/test/java/me/rosuh/easywatermark/platform/AndroidIconPersistenceTest.kt'
# Expect empty against the committed candidate tip.
```

## Gates

### Blocking

| Gate | Command / check |
|---|---|
| Ownership fitness | `./scripts/verify-ownership-fitness.sh` (fail-closed; adversarial missing-path) |
| Android + Desktop | `./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:desktopTest :desktopApp:compileKotlin --max-workers=8` |
| Shared iOS tests | `./gradlew :shared:iosSimulatorArm64Test --max-workers=8` |
| Xcode generic Simulator | `xcodebuild … CODE_SIGNING_ALLOWED=NO build` |
| Desktop headless | `./gradlew :desktopApp:run --args='--headless' --max-workers=8` |
| Backup policy structural | Manifest + rule XML |
| Hygiene | No evidence in range; three trackers |

### Informational

| Gate | Notes |
|---|---|
| `./gradlew :app:lintDebug` | Report honestly; PR policy fail-open |

## Stop conditions

- Remote base ≠ `8aa588d4` → block
- Replay conflict → block with SHA + paths
- Non-allowlist product-tree mismatch → block
- Blocking gate needs product-source (non-test) change → block
- Dirty main worktree would be touched → block
- Room schema or macrobenchmark fixture would be removed → block

## Coordinator / owner stages (overall L0)

1. **Worker reconstruction** — sanitized history + L0 allowlist docs/script.
2. **Worker short closeout (attempt 3)** — test fixes + docs/fitness left unstaged/uncommitted for review.
3. **Coordinator commits (required):**
   1. one **test-only** integration-fix commit;
   2. one final L0 docs/fitness commit with exact subject:
      `docs(l0): replace migration evidence archive with lightweight status`
4. **Push precondition (coordinator):** only when
   `git rev-parse origin/feat/migrate_to_compose` equals `8aa588d471d938b2602ddb5a92e6da7b84f72e32`,
   perform an **ordinary fast-forward** update of the PR branch. **Never** `git push --force`,
   **never** `--force-with-lease`, **never** history rewrite on the remote.
5. **PR #358 update** — coordinator only after FF push succeeds.
6. **Merge / tag / release** — owner only.

## Worker acceptance checklist (attempt 3)

- [x] 58 product commits replayed; 49 filtered-empty skipped
- [x] Product tree ≡ `dc9ecf34` outside allowlist (+ authorized tests)
- [x] Room schema + macrobenchmark fixture retained
- [x] Exactly three tracker files with owner-approved names
- [x] Desktop wiring guard → `refreshPreviewLight` / `DesktopPreviewRaster`; preview must not `runSaveFlow`
- [x] Android FileProvider production-constructor isolation (no static strategy leakage)
- [x] Ownership fitness fail-closed + adversarial missing-path; exit 0
- [x] Combined Android/Desktop suite exit 0 (once)
- [x] Worker edits left **unstaged / uncommitted** for coordinator review
- [x] No push; no PR #358 mutation

## Completion format

```text
Latest validated source SHA: 784e1c1b9e4e0ddb94d0ef6c9d4d86ae1315ff02
Status: READY_FOR_PUBLICATION
Trackers: 13-roadmap + 13-status + 58-l0-integration-pr-landing-plan
Gates: ownership=PASS android=105/0 desktop=333/0 hygiene=PASS
Publication: not started (ordinary FF only when origin == 8aa588d4)
```
