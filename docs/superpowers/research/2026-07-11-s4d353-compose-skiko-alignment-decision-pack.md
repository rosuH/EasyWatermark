# S4d-353 — Owner decision pack: iOS Compose/Skiko Phase A gate

**Date:** 2026-07-11
**Type:** decision pack only — **no option accepted or recommended**
**Not** Phase A/B/parity complete. No implementation brief.

**Binding hard constraints:** `codex-goal-v2.md` §6 (Android native text/icon/composition stay; persisted bytes sacred; no silent golden rebaseline; new deps owner-gated; consumer-first ≥2 production consumers; **no S4d-338 retry / Compose–Skiko change without owner**); §7.3–7.4 (block = iOS text/full-root critical edge; lane-switch elsewhere open but non-text already exhausted). Forbidden without owner: change Compose/Skiko versions.

Codex **must not** implement, bump versions, invent substitutes, or reorder plan until owner signs an option.

---

## 1. Exact blocker

### Runtime (S4d-338)

Production attempt to host commonMain `TextContentOption` on iOS (then **fully reverted**). Evidence: `build/s4d338-text-xcuitest-r{2,3,4}.xcresult`; `findings.md` “iOS CMP text-input linker block”; `progress.md` S4d-338. iOS 27.0 XCUITest:

| Run | Trigger | Failure |
|---|---|---|
| r2 | Material3 `ModalBottomSheet` | Missing `LocalKeyboardOverlapHeight` |
| r3 | Compose `Dialog` + `navigationBarsPadding()` | Missing `LocalSafeArea` |
| r4 | Insets bypassed; Compose text visible | Focus → `SkikoPlatformTextInputMethodRequest` lacks `unclippedTextOffsetInRoot` |

Related: S4d-321 `GalleryDialogShell` default Scaffold insets → `LocalSafeArea` crash (witness uses explicit `WindowInsets()` only). **Compile ≠ iOS sheet/IME safety.** Non-text CMP already runs.

### Locked versions (`gradle/libs.versions.toml`)

| Item | Version |
|---|---|
| Kotlin | `2.3.20` |
| Compose Multiplatform | `1.11.1` |
| AndroidX Compose BOM | `2026.05.01` → UI **1.11.2** on Android (catalog note) |
| Material3 (app catalog pin) | `1.4.0` |
| AGP | `8.13.2` |

`:shared` = CMP plugin + `compose.runtime/ui/material3`. `:app` = enforced BOM + `org.jetbrains.compose.*` → `androidx.compose.*` substitution. Skiko is **transitive** (iOS/desktop CMP); **no separate catalog pin**. Text/IME fix implies owner-approved **CMP (+ likely Kotlin) alignment**, not a host-only patch. Desktop Skiko must stay out of `:app` runtime.

---

## 2. Affected production surfaces

| Surface | Shared API | Production today |
|---|---|---|
| Watermark text | `TextContentOption` (sheet + `OutlinedTextField`) | SwiftUI `TextField` + Apply → `WatermarkWorkflow` / DataStore |
| Templates | `TemplateListSheet` / host (sheet + `AlertDialog` + text) | SwiftUI + `IosTemplateBridge` — S4d-346 **drop-in NO-GO** |

**Not blocked (already CMP or system):** launch, icon, sliders, tile/typeface/style, color swatches (`showCustomInput=false`), preview, `SavedOutputActions`, PhotosPicker — see S4d-352 matrix.

Separate product decisions: iOS About/gallery/full editor root. Android native renderer **not** reopened by this gate.

---

## 3. Why safe non-text work is exhausted

S4d-352: all production iOS **interactive non-text** controls with safe commonMain APIs already have iosMain hosts. Left: pickers, captions/status, **text**, **templates**. A1 pure Android wrappers closed (S4d-351). Further Phase A **control** progress on iOS needs A/B/C below.

---

## 4. Mutually exclusive owner options

### A — Align Compose/Skiko (required Kotlin) so S4d-338 APIs work on iOS

Unblock `ModalBottomSheet` / safe-area locals / focused `OutlinedTextField`.
- **Android renderer / persistence:** must stay native + byte-identical.
- **Goldens:** if Android Compose lineage moves, **re-run** local strict FNV; rebaseline **only** with owner sign-off. Re-run Desktop/iOS perceptual tests as needed.
- **Deps:** named exception to §6.11 / S4d-338 ban; document pin + rollback.
- **Gates:** full multiplatform Gradle; iOS XCUITest text (+ templates if drop-in later); Android assemble + non-strict tests; screenshots for unlocked paths.
- **Rollback:** revert catalog pins; keep SwiftUI until green.

### B — Permanent explicit native exception (formalize status quo)

iOS watermark **text** + **templates UI** stay SwiftUI + bridges; shared text/sheet APIs remain Android+Desktop until reopen.
- **Android / goldens / deps:** no change if policy-only.
- **Gates:** existing SwiftUI XCUITest remains proof.
- **Phase A:** this edge closed **with exception**, not “full shared UI.”
- **Rollback:** N/A; reopen only via new owner decision.

### C — Deliberately scoped new substitute (only if share without full IME)

New commonMain APIs **without** sheet/dialog/focused text (e.g. list-only templates; display row + platform text slot). Not a drop-in of existing sheet APIs. Needs §6.12 dual production consumer or explicit waiver.
- **Risk:** dual-API drift (Android/Desktop keep full sheet).
- **Deps:** prefer no bump; design still owner-gated.
- **Gates:** new hosts + XCUITest; no Android/Desktop sheet regression.
- **Rollback:** delete new API; keep SwiftUI.

**Non-options:** silent inset hacks without IME fix; invent product roots / `WatermarkModeActions` as progress; Android draw-swap; unapproved golden rebaseline.

---

## 5. Owner must decide

1. **A, B, C, or defer** (with reason/date)?
2. If **A:** exact CMP/BOM/Kotlin targets; accept full iOS text XCUITest + multiplatform gates; authorize strict golden rebaseline **only if** hashes move?
3. If **B:** durable home (ADR vs findings); stop scheduling iOS CMP text/templates without reopen?
4. If **C:** minimal surface; second platform consumer or waiver; keep Android/Desktop sheets unchanged?
5. Confirm **Android native renderer + persisted bytes untouched** under any choice.

---

## 6. Codex must not

Accept/implement A–C; bump Compose/Skiko/Kotlin in a UI slice; retry production iOS `TextContentOption` / `TemplateListSheet` on current mix; rebaseline goldens or touch DataStore/Room; invent roots; push/merge; treat this pack as product code.

---

## 7. Recommendation

**None accepted.** Evidence is decision-ready; residual iOS Phase A control progress waits on owner choice alone.

**Refs:** `codex-goal-v2.md` §6–7; `findings.md` / `progress.md` S4d-338; S4d-346/352 research; `libs.versions.toml`; `ContentView.swift`; `TextContentOption.kt`; `TemplateListSheet.kt`.
