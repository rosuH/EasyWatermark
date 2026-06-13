# View→Compose Migration — Review Report

**Date:** 2026-06-13 · **Branch:** `feat/migrate_to_compose` · **Status:** uncommitted, build-green, ready for review
**Purpose:** review-ready summary of this work period's changes for a colleague to assess before deciding next steps.

---

## 0. Context (one paragraph)

EasyWatermark is mid-migration from legacy Android Views/Fragments to Jetpack Compose on this branch. This work period (2026-06-12 → 06-13) advanced that View→Compose migration from ~60% to ~80% — migrating the About and Open-Source screens to Compose, consolidating the ACTION_SEND share-in entry into the Compose Activity, fixing UI-parity gaps against the production release (v2.10.0), and standing up an AI-friendly documentation system (CLAUDE.md + ADRs + CONTEXT.md). A separate, longer-horizon **Compose Multiplatform (CMP)** plan (Android+Desktop+iOS, milestones C2–C6) was researched and designed but **not** implemented — see `docs/superpowers/plans/2026-06-12-cmp-migration-plan.md` and `docs/adr/`.

**Important caveat for the reviewer:** the goal was originally given via an automated "complete the migration" hook that the developer later explicitly waived. The migration is intentionally **not** finished — it is at a clean, verified checkpoint with the final block (MainActivity retirement) designed but not executed.

---

## 1. Changes made THIS work period (the ones to review)

### A. UI parity with production v2.10.0
| File | Change | Why | Verified |
|---|---|---|---|
| `ui/Theme.kt` | `AppTheme(darkTheme)` default `isSystemInDarkTheme()` → **`true`**; wired `dynamicColor = CMonet.isDynamicColorAvailable()` at the call site | Production is forced-dark (`Theme.Material3.Dark`, no DayNight); the Compose branch was following system → light drift. Dynamic color (Material You) was hard-coded off. | S22+ (AndroMeld) + emulator |
| `ui/Color.kt` | ~15 dark tokens aligned to master `res/values/colors.xml` exactly (surface `#15130E→#1D1B16`, onSurface, primary `E5C50E→E4C50D`, etc.) | Token drift from production | emulator (editor self-heals) |
| `ui/LaunchScreen.kt` | "Choose Images" button `shape = RectangleShape`; **added `onGoAbout` callback** so the launch-screen info button navigates to the Compose About screen instead of `startActivity(AboutActivity)` | Production buttons are sharp-cornered (0dp); single-entry About | emulator |

### B. Editor parity fixes
| File | Change | Why | Verified |
|---|---|---|---|
| `ui/EditorScreen.kt` | (1) filmstrip gate `imageList.size > 1` → **`isNotEmpty()`** (show thumbnail strip for single image too); (2) Content-tab `onGoAboutScreen`/text wiring; text option now opens a modal | Production shows the strip for one image; align text editing to production modal | emulator |
| `ui/compose/TextContentOption.kt` | Rewrote inline `TextField` → **read-only text row that opens a modal "Edit watermark" sheet** (title + field + Confirm), reusing `dialog_title_edit_watermark`/`tips_confirm_dialog` | Production opens a modal on tap, not an always-inline field | emulator (open→edit→confirm→close) |
| `ui/save/SaveExportSheet.kt` | Added `imageUris: List<Uri>` param; placeholder "N image(s) selected" Box → **Coil `AsyncImage` thumbnail `LazyRow`** | Production shows real thumbnails | emulator |

> Note: `SaveExportSheet.kt` shows as a large diff because it was a 2-line stub in the git index (staged earlier, pre-this-period) and the working tree is the full implementation; my change is the thumbnail strip + `imageUris` param on top of that.

### C. About / Open-Source → Compose (new screens)
| File | Change | Why | Verified |
|---|---|---|---|
| `ui/about/AboutScreen.kt` (**new**) | Full Compose About screen: top bar (back + logo), Information/About sections (Version, Rate, Feedback, Changelog, Open source, 隐私政策, Privacy), two switches (dynamic color, show bounds), dev/designer footer; links via `Activity.openLink`, toggles via `AboutViewModel` | Replace legacy `AboutActivity` | S22+ + emulator (both entry points), visual match vs prod baseline |
| `ui/about/OpenSourceScreen.kt` (**new**) | Compose Open-Source screen: 4 library cards (ColorPickerView/Glide/Material Components/Compressor) opening repo links | Replace legacy `OpenSourceActivity` | emulator |
| `ui/ComposeMainActivity.kt` | Added `AboutViewModel`; NavHost `composable("AboutScreen")` + `composable("OpenSourceScreen")`; `onGoAboutScreen` and About's `onOpenSource` now navigate in-Compose instead of `startActivity(...)` | Route both About + OpenSource through Compose Navigation | emulator |

### D. ACTION_SEND share-in consolidation
| File | Change | Why | Verified |
|---|---|---|---|
| `ui/ComposeMainActivity.kt` | `handleShareIntent()` + `onNewIntent` override + `pendingShareUris` (mutableStateOf) → `LaunchedEffect` bridges to `navController.navigate("EditorScreen")`. Handles **ACTION_SEND and ACTION_SEND_MULTIPLE** (EXTRA_STREAM/clipData via `IntentCompat`) — fixing the long-standing single-image-only gap. Uses `viewModel.updateImageList(uris)` (NOT `SystemPickerImageSelected`, which assumes a MediaStore `_ID` and would silently fail on arbitrary share uris). | Move share-in from legacy MainActivity to the Compose Activity (single entry) | emulator: `adb am start -a SEND --eu EXTRA_STREAM <uri> -n .../ComposeMainActivity` → shared image loads in editor with watermark |
| `AndroidManifest.xml` | Moved ACTION_SEND + added ACTION_SEND_MULTIPLE intent-filter onto `ComposeMainActivity`; `MainActivity` loses its filter (`exported=false`) | Single entry point (ADR-0016 Option A) | — |

**Build:** every step verified with `./gradlew :app:assembleDebug` green. Net code delta this period ≈ the diff in §3 minus the pre-existing files in §2.

---

## 2. Pre-existing working-tree changes — NOT made this period (do not attribute to this review)

These were already modified/staged in the working tree at the start and were **not** touched (or only read) this period. Flagging so the reviewer doesn't conflate them with the migration work above:

- `data/model/ViewInfo.kt`, `ui/MainViewModel.kt`, `ui/widget/WaterMarkImageView.kt` — pre-existing edits (engine/export debt; relevant to the CMP plan's C2 but untouched here).
- `.gitignore`, `app/src/main/assets/adi-registration.properties` — pre-existing.
- `ComposeMainActivity.kt` / `EditorScreen.kt` / `SaveExportSheet.kt` also contained pre-this-period edits; this period added the About/OpenSource/share/parity wiring on top.

(Recommend the reviewer `git diff` per file if exact authorship matters; the migration-relevant additions are enumerated in §1.)

---

## 3. Verification

- **Real device (Galaxy S22+, Android 16) via AndroMeld MCP:** theme fix + dynamic color confirmed on launch screen; app launches clean (process RUNNING, no Room errors). Screenshots: `parity-shots/compose/s22-launch-dark.png`, `s22-launch-dynamic.png`.
- **Emulator (Medium_Phone, API 29) via adb:** editor theme self-heal, single-image filmstrip, save-sheet thumbnails, text-edit modal (open→confirm→close), About (both entry points) + OpenSource screens, ACTION_SEND share-in end-to-end, quality-default=80 on clean install. Screenshots: `parity-shots/compose/emu-*.png` (8 files).
- **Resource discipline:** emulators run headless + `--max-workers=8` + killed after each batch (a prior session's input-freeze incident is recorded in agent memory; mitigations held — zero leftover processes).
- **Two audit false-positives cleared:** "quality default 40" was residual DataStore from a slider drag (clean install = 80); default-emoji is identical 👋 both sides (comparator misread).

---

## 4. Current status

- **View→Compose ~80% complete.** Compose path now covers: Launch, Editor, Gallery, Save sheet, Text edit, **About, OpenSource**, **ACTION_SEND share-in**.
- All changes **uncommitted, build-green**. Suggested commit breakdown ready (theme/parity / about+opensource / share-in).
- 16 ADRs in `docs/adr/` capture every decision; session log in `progress.md`; parity backlog in `docs/superpowers/research/2026-06-13-ui-parity-backlog.md`.

---

## 5. Remaining work (the final block — designed, not executed)

**MainActivity retirement** (`docs/adr/0016-...md`) — the last View→Compose step, deliberately left for a focused change because it touches production-critical paths:

1. **Crash-recovery screen → Compose.** Discovery: it is *already effectively dead* — `MyApp` crash handler relaunches HOME + `exitProcess`, so the next launch goes through LAUNCHER = `ComposeMainActivity`, but `recoveryMode` is only checked in `MainActivity.onCreate`. Migrating the launcher to Compose silently broke it earlier. Fix: port the `recoveryMode` check + recovery UI into `ComposeMainActivity`.
2. **Delete the legacy Activity chain:** `MainActivity` (820 lines, now entry-less after the share-in move), `AboutActivity`, `OpenSourceActivity`, their layouts, and manifest entries. They are now referenced only by each other.

**Verification plan for that block (in ADR-0016):** `adb am` share-intent tests (single + multiple), `recoveryMode` simulation, AndroMeld real-device cross-app share — all green before deleting anything.

### Open decisions for the team (from ADR-0015, my recommendations noted, each cheaply reversible)
- **Top-bar logo vs back arrow** (kept back arrow — Compose nav best practice; system back verified working).
- **TileMode segmented vs radio** (kept M3 segmented — modern equivalent).
- **Text editing modal** (implemented modal for parity).
- Plus: whether to raise minSdk 23→29 (ADR-0008), and the whole CMP go/no-go (C2–C6, multi-month, ADR-0001–0016 + plan).

---

## 6. Risks / things to scrutinize

- **`ViewInfo` / export-scale coupling** (`1/MSCALE_X`, both axes) is real pre-existing debt; the CMP plan's C2 rewrites the watermark engine in commonMain to remove it. Not addressed here.
- Share-in copies arbitrary-app uris — read-permission scope; ADR-0016 notes copy-to-cache (R7) for the MainActivity-retirement step.
- AGP 9 / CMP-9547 and Nav3 KMP-readiness are gating items for the CMP phase (ADR-0002/0003), not this phase.
- All migration changes are uncommitted on one branch — recommend committing the verified parts before the next big step.

---

## 7. Where everything is

- **Decisions:** `docs/adr/0001`–`0016` (+ README index). 0013 (desktop ship?) and 0016 (MainActivity) are `Proposed`.
- **Plans:** `docs/superpowers/plans/2026-06-12-cmp-migration-plan.md` (CMP C1–C6).
- **Parity backlog + evidence:** `docs/superpowers/research/2026-06-13-ui-parity-backlog.md`, `parity-shots/`.
- **CMP readiness audit (13-agent):** `docs/superpowers/research/2026-06-12-cmp-readiness-audit.json`.
- **Session log:** `progress.md`. **Domain glossary:** `docs/CONTEXT.md`. **Agent guide:** `CLAUDE.md`.
- **Installed skills** (`.claude/skills/`): migrate-xml-views-to-jetpack-compose, adaptive, navigation-3.
