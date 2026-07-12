# 01 — A5a iOS production EditorScreenShell route

**What to build:** iOS production editor uses shared `EditorScreenShell` (`showPhotoStrip = false`) in **one** ComposeUIViewController with a flat scrollable options column (real shared option composables + real Skiko preview). System pickers, Share/Save, SwiftUI Templates, and `WatermarkWorkflow` stay outside shared state. Phase A route-of-record only — not v2.10.0 pixel parity. If a hard contract/runtime issue remains, produce an owner decision package rather than a silent permanent laundry-list.

**Blocked by:** None — can start immediately.

**Status:** **complete** (S4d-383 / A5a accepted 2026-07-12)  
**Does not complete:** A5 / Phase A / Phase B / §9 DoD.

## Acceptance checklist

- [x] Production iOS editor chrome is `EditorScreenShell` (not multi-host control list), or owner-signed decision package is recorded in this ticket
- [x] Controls remain user-equivalent: text, icon, degree, tile, alpha, color, size, gaps, typeface, style
- [x] XCUITest contracts preserved or deliberately updated; full suite still meaningful
- [x] Guardrails: Android native renderer untouched; no new deps; no shared VM/nav/IO layer; no TextConfirmGate workarounds

## Implementation (scoped files)

| File | Role |
|------|------|
| `shared/.../IosSharedComposeHost.kt` | Production `IosEditorScreenHost`: one `ComposeUIViewController` + `AppTheme` + `EditorScreenShell(showPhotoStrip=false)` + real Skiko preview + flat options (`heightIn(max = 300.dp)`). Sticky Share/Save. Exact ARGB hex color labels. Typeface/style segments **without** `mergeDescendants`. |
| `iosApp/iosApp/ContentView.swift` | Surgical production host `SharedComposeEditorScreen` + fill-height editor + compact Templates strip. DEBUG-only `-sharedComposeWitnesses` route: full-screen scrollable witness surface (not appended under production editor). Production path untouched when flag absent. |
| `iosApp/iosAppUITests/PickerFlowUITests.swift` | Scroll routing (options vs templates); segmentChoice by named Fill/Stroke/Normal/Bold; exact text-content marker; color swatch exact hex; witness + templates contracts. |

**Not in scope / untouched by design:** Android native `WatermarkRenderer` production path; Desktop; new deps; shared VM/nav/IO; compose-resources; Weblate strings; TextConfirmGate workarounds.

## Verification commands and evidence

Evidence root: `build/s4d383-a5a-final/`.

### Full iosAppUITests — 19/0

```bash
export DEVELOPER_DIR=/Applications/Xcode-27.0.0-Beta.app/Contents/Developer
xcodebuild test \
  -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'id=CF9CE125-D6B2-4D40-B634-56C5E5B65CF4' \
  -derivedDataPath build/s4d383-a5a-final/ios-witness-ddata \
  -resultBundlePath build/s4d383-a5a-final/ios-full.xcresult \
  CODE_SIGNING_ALLOWED=NO \
  '-only-testing:iosAppUITests'
```

| Artifact | Result |
|----------|--------|
| `35-full-xcodebuild.log` | `Executed 19 tests, with 0 failures` · `** TEST SUCCEEDED **` (15:21) |
| `35-full-exit.txt` | present (completed run) |
| `ios-full.xcresult` | `xcrun xcresulttool get test-results summary` → `result: Passed`, **19 passed / 0 failed**, iPhone 17 Pro / iOS 27.0 |
| Focused witnesses (pre-full) | `34-witness-xcodebuild.log` — launch/gallery/about/editor **4/0** after DEBUG witness route |

### Forced Gradle (post–ContentView 15:16; do not cite older 30/31 logs)

```bash
./gradlew --max-workers=8 \
  :shared:compileKotlinIosArm64 \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:desktopTest \
  :shared:iosSimulatorArm64Test \
  --rerun-tasks
# → 36-shared-gates.log · EXIT_SHARED:0 · BUILD SUCCESSFUL 19s · 33 tasks executed

./gradlew --max-workers=8 \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  --rerun-tasks
# → 37-app-gates.log · EXIT_APP:0 · BUILD SUCCESSFUL 15s · 79 tasks executed

./gradlew --stop
# → 38-gradle-stop.log · 1 Daemon stopped
```

| Artifact | Result |
|----------|--------|
| `36-shared-gates.log` + `36-shared-gates-exit.txt` | EXIT_SHARED:0 |
| `37-app-gates.log` + `37-app-gates-exit.txt` | EXIT_APP:0 |
| `38-gradle-stop.log` | daemon stopped |

### Diff hygiene

- `git diff --check` on the three scoped source files: clean.
- Commit payload: three scoped sources + this ticket only.
- Excluded: `docs/superpowers/research/2026-07-11-project-branch-goals-progress.md` (user-owned, untracked).

## Grok visual review (personal image open — not byte-count)

Attachments exported from `ios-full.xcresult` to `build/s4d383-a5a-final/35-full-attachments/named/`.

| Area | Observation | Verdict |
|------|-------------|---------|
| **Preview usable height** | Fixture is a clear mid-upper square with readable diagonal amber tiling on all four color blocks; not crushed under options/Templates. Sticky Share/Save sit below preview with breathing room; Templates is a compact bottom strip (~2 rows). | **PASS** (P1-3 rebalance holds) |
| **Typeface / style** | `07-…typeface-bold` + `06-…text-style-stroke`: discrete Normal/Bold/Italic/BoldItalic and Fill/Stroke labels; selected state checked/filled; not one merged control. | **PASS** |
| **Text edit / Confirm** | Host field updates to exact marker (`S4d378-B249A138`); PNG size drops after re-render (Single mode). Residual: on-image watermark footprint nearly invisible on solid fixture colors after confirm — field + tests prove commit path; sparse Single-mode visual, not Confirm failure. | **functional PASS** + residual note |
| **Templates S/A/D** | `20`→`21`→`22`→`23`: Save adds third row; Apply sets field to `S4d234-628D466C`; Delete returns to two seed rows; blue row labels + red trash + Save current independently visible. | **PASS** |
| **Share / Save** | Gold sticky Share + Save to Photos; `03-after-save` shows green “✓ Saved to Photos”; `04-share-sheet` is real system share sheet (PNG ~33 KB). | **PASS** |

## Residual / non-blockers for ticket 01

- DEBUG shell witnesses are a full-screen DEBUG-only route (`-sharedComposeWitnesses`); production path does not host them under the fill-height editor.
- Old per-control Compose hosts may still exist as residual/debug API in `IosSharedComposeHost.kt`; production ContentView uses only `IosEditorScreenHost`.
- Real PHPicker grid-cell selection remains the pre-existing beta-toolchain XCUITest limitation (fixture seam).
- **Not** Android v2.10.0 1:1 parity; Phase A route-of-record only.

## Next

- Tickets **02** (gallery), **03** (about), **04** (desktop) are parallel-ready under the local program.
- Ticket **05** A5 closeout still requires green multi-platform closeout gates + explicit PASS — do not claim A5 from this ticket alone.
