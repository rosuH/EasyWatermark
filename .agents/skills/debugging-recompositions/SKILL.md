---
name: debugging-recompositions
description: Use this skill to find which Jetpack Compose composables are recomposing and why, using Android Studio Layout Inspector recomposition counts and skip counts, the per-parameter Argument Change Reasons (Changed / Unchanged / Uncertain / Static / Unknown) introduced in Android Studio Hedgehog and later, and runtime `@TraceRecomposition` from `compose-stability-analyzer` for production-like measurement. Walks through enabling counts, mapping each Argument Change Reason to a fix, and confirming the result in a release build. Use when the developer says "this should be skipping but isn't", "I want to see recomposition counts", asks what "Uncertain" or "Unknown" means in the inspector, or needs to confirm a stability or strong-skipping fix actually worked end-to-end.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - recomposition
  - layout-inspector
  - argument-change-reasons
  - trace-recomposition
  - android-studio-hedgehog
  - debugging
  - compose-stability-analyzer
---

# Debugging Recompositions — make the invisible visible, then map status to action

Recomposition is invisible by default. A composable that re-runs sixty times a second to redraw an animation looks identical in source to one that should re-run zero times when its parent ticks. Three layers of instrumentation make it visible: **Layout Inspector** recomposition counts and skip counts (Android Studio, debug build), Layout Inspector **Argument Change Reasons** (Hedgehog and later, per-parameter Changed / Unchanged / Uncertain / Static / Unknown classifications), and runtime **`@TraceRecomposition`** from `compose-stability-analyzer` for release / R8 / real-device measurement.

This skill is the diagnostic layer. It does not fix recompositions; it tells the developer which composable is recomposing and which parameter is responsible. Once the param is named, the fix lives in a sibling skill — stability for unstable types, strong skipping for lambda capture, or phase deferral for state reads in the wrong phase.

## When to use this skill

- The developer says "this should be skipping but it's not" or "this re-runs every time the parent ticks".
- The developer wants to count recompositions of a specific composable to confirm a fix worked.
- The developer asks what "Uncertain", "Unknown", "Static", or "Unchanged" means in the Layout Inspector recomposition reasons panel.
- A PR claims a stability or strong-skipping fix; the reviewer needs to confirm the recomposition actually went away in release.
- The user mentions "recomposition count", "Layout Inspector", "Argument Change Reasons", "Hedgehog", "@TraceRecomposition", or "why is this recomposing".

## When NOT to use this skill

- The composable is recomposing because of an unstable type — once this skill names the offender, the actual fix is in `../../stability/diagnosing-compose-stability/SKILL.md` and `../../stability/stabilizing-compose-types/SKILL.md`.
- The composable is recomposing because of a lambda capture or non-skippable scope — fix work lives in `../using-strong-skipping-correctly/SKILL.md`.
- The composable is recomposing because a state read happened in the wrong phase (Composition vs Layout vs Draw) — fix work lives in `../deferring-state-reads/SKILL.md`.
- The team needs a CI gate against future regressions — use `../../stability/enforcing-stability-in-ci/SKILL.md`.

## Prerequisites

- Android Studio **Hedgehog (2023.1.1)** or later for Argument Change Reasons. Earlier Studio versions have recomposition counts but not the per-parameter reason panel.
- A **debug** build for Layout Inspector (Layout Inspector requires debug). Read counts as **directional** signal only; debug builds run interpreted with Live Literals which inflate recomposition counts.
- `androidx.lifecycle:lifecycle-runtime-compose` available so `collectAsStateWithLifecycle` can replace plain `collectAsState` when a flow turns out to be the offender.
- For release-mode confirmation: the runtime tracing setup from `../../measurement/tracing-recompositions-at-runtime/SKILL.md` (the `compose-stability-analyzer` runtime + `ComposeStabilityAnalyzer.setEnabled(true)` in `Application.onCreate`).
- Familiarity with the Compose Compiler reports format (see `../../stability/diagnosing-compose-stability/SKILL.md`) — Layout Inspector classifications mirror the same stability vocabulary.

## Workflow

### 1. Enable recomposition counts in Layout Inspector

Run the app on a connected device or emulator in **debug**. Open Android Studio → **Tools** → **Layout Inspector**. In the Layout Inspector toolbar, toggle:

- **Show recomposition counts** — adds a "Recompositions" column to the component tree and overlays a count badge on the rendered tree.
- **Show recomposition skip counts** — adds a sibling column showing how many times the composable's skip guard fired (i.e. it was invoked but its body was skipped).

Both are off by default. With both on, the tree displays `<recompositions>/<skips>` per node, which is the primary signal.

The healthy ratio is "skips ≫ recompositions". A composable with `100/0` (one hundred recompositions, zero skips) is the smoking gun — every parent tick re-ran its body without any skip guard firing. A composable with `100/95` is doing fine — only five recompositions triggered actual work.

### 2. Reproduce the symptom while watching counts

Counts are cumulative. Reset them via the Layout Inspector "Reset" button before reproducing. Then perform the user action that the developer suspects is causing the jank — scroll, tap, animation tick — and watch which composables' counts climb.

The composable with the disproportionate count growth is the suspect. Note its name and parameter list before moving on.

### 3. Inspect Argument Change Reasons

Click the suspect composable in the Layout Inspector tree. The right-hand panel now shows a **"Recomposition reasons"** section listing each parameter with one of five classifications:

| Status | Meaning | Likely action |
|---|---|---|
| **Static** | Compile-time constant; never invalidates. | None. This param is not the cause. |
| **Unchanged** | Value compared equal to the previous composition (same identity or `equals`). | None. This param did not trigger the recomposition. |
| **Changed** | Value compared not-equal to the previous composition. | Investigate whether the change was meaningful or accidental (e.g. a `copy()` with identical content failing `equals` because a field is `MutableList`). |
| **Uncertain** | Compose cannot determine whether the parameter changed since the last composition; typically the param type is unstable to the compiler so the runtime fell back to identity equality (`===`) and the comparison was inconclusive. | Stabilize the type (annotate with `@Stable` / `@Immutable`, or replace `List<T>` with `ImmutableList<T>` / `PersistentList<T>` so structural `equals` applies) — or accept the recomposition. |
| **Unknown** | The compiler could not statically classify the type's stability. The runtime fell back to `===`. | Run `../../stability/diagnosing-compose-stability/SKILL.md` to find why the type is unclassified — usually a separately-compiled module without `@StabilityInferred`, an interface, or an unannotated POJO from a Java library. |

The status names what to do next; do not jump to a fix without naming the status.

### 4. Map status to action

Decision tree once a status is identified:

- **Status = Changed.** The value really did change. Ask whether the change was meaningful: did the user input change, or did `copy()` produce a structurally-different object the developer thought was identical? Common offender: a `Flow` collected without `distinctUntilChanged()` emitting equal values that fail reference equality.
- **Status = Uncertain.** Identity changed, content might match. Annotate the type or use a stable collection. See `../../stability/stabilizing-compose-types/SKILL.md`. Frequently the smoking gun for an instance-equality miss after a `data class copy()`.
- **Status = Unknown.** The compiler did not classify. Run the stability diagnosis. See `../../stability/diagnosing-compose-stability/SKILL.md`. Most common causes: interface-typed parameter, separately-compiled Java POJO, generic parameter with no instance to substitute.
- **Status = Static.** No action; the param is a compile-time constant.
- **Status = Unchanged.** No action; the param was equal across the recomposition. The cause must be elsewhere (another param, or the parent's restart scope re-invoking unconditionally).

### 5. Confirm in release with @TraceRecomposition

Layout Inspector counts come from a debug build. Debug builds have Live Literals (constant values become getters), interpreted Compose runtime, and no R8. Counts are useful as a directional signal but not as a final number. For release-mode confirmation, instrument the suspect composable with `@TraceRecomposition` from `compose-stability-analyzer` and re-run the scenario on a release + R8 build. Cross-link `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for the full setup; the two-line summary:

```kotlin
@TraceRecomposition(traceStates = true)
@Composable
fun SnackRow(snack: Snack, onClick: (Long) -> Unit) { /* ... */ }
```

Logcat under `Recomposition` tag will print one line per recomposition, with each parameter and whether it changed. The release-mode count is the ground truth; quote it (not the debug count) when reporting that a fix worked.

## Patterns

### Pattern: do not draw conclusions from debug counts alone

```text
# WRONG
"Layout Inspector shows SnackRow at 100/0 in debug — fix shipped."

# WRONG because: debug builds run interpreted with Live Literals turning constants into
# getters that defeat compile-time folding and inflate recomposition. The same composable
# in release + R8 may run 5/95. Always confirm the final number in release with @TraceRecomposition.
```

```text
# RIGHT
"Layout Inspector showed SnackRow at 100/0 in debug. After the fix, debug shows 5/95
and release-mode @TraceRecomposition confirms 0 recompositions across a 30-frame scroll."
```

See `../../measurement/testing-compose-in-release-mode/SKILL.md` for why debug counts lie.

### Pattern: name the status, then act

```text
# WRONG
"FilterBar is recomposing too much. I'll mark Filter @Stable and see if it helps."

# WRONG because: the developer skipped naming the Argument Change Reason. If the status was
# Unknown, the type needs classification (probably from an external module); if it was
# Uncertain, the type needs @Immutable + ImmutableList; if it was Changed, the upstream
# producer is mutating where it shouldn't. Each requires a different fix; guessing wastes a cycle.
```

```text
# RIGHT
"FilterBar is recomposing per-frame. Layout Inspector reports the `filter` param as Uncertain.
The Filter type holds List<String>; replacing with ImmutableList<String> + @Immutable should
let equals() catch structural equality. Confirm with @TraceRecomposition in release."
```

### Pattern: ignoring "Uncertain" is the most common mistake

"Uncertain" is the single most diagnostically-rich status. It says: "the compiler thought this type was stable, the runtime ran the comparison, and identity differed." That is almost always a `copy()` or a builder producing structurally-equal-but-identity-different instances, plus an `equals` that does not apply because a sub-field is unstable.

```kotlin
// WRONG — declared @Immutable but the field is mutable
@Immutable
data class Filter(val tags: List<String>, val sort: SortOrder)
// WRONG because: List<String> is unstable; @Immutable is a contract the developer broke.
// Layout Inspector reports `filter` as Uncertain on every recompose; structural equals
// does not run because a sub-field is unstable.
```

```kotlin
// RIGHT
@Immutable
data class Filter(val tags: ImmutableList<String>, val sort: SortOrder)
```

### Pattern: chain into runtime tracing for end-to-end confirmation

```kotlin
// RIGHT — annotate the suspect, run release with R8, capture logcat
@TraceRecomposition(traceStates = true)
@Composable
fun FilterBar(filter: Filter) { /* ... */ }
```

```bash
adb logcat -s Recomposition:D
# Expected after the fix: a single "[Recomposition #1]" on initial composition,
# then no further entries during the scroll scenario.
```

See `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for the full instrumentation skill.

### Pattern: use the skip count, not the recompose count, as the health metric

A composable at `0/0` is dead code or never reached. A composable at `100/0` is broken. A composable at `100/95` is healthy — only five real recompositions out of one hundred parent ticks. The skip-to-recomposition ratio is the metric to track over time, not the absolute count.

## Mandatory rules

- **MUST** name the Layout Inspector Argument Change Reason status (Changed / Unchanged / Uncertain / Static / Unknown) when reporting a recomposition finding. "It recomposes a lot" is not a finding; "param `filter` is Uncertain on every parent tick" is.
- **MUST NOT** treat debug Layout Inspector recomposition counts as the ground-truth number. Debug uses interpreted Compose + Live Literals; the count is directional only. Confirm in release with `@TraceRecomposition`.
- **MUST** reset Layout Inspector counts before reproducing the scenario. Cumulative counts from earlier app states pollute the diagnosis.
- **MUST** map each status to the correct sibling skill: Uncertain / Unknown → `../../stability/diagnosing-compose-stability/SKILL.md`, Changed (lambda capture) → `../using-strong-skipping-correctly/SKILL.md`, Changed (state read in wrong phase) → `../deferring-state-reads/SKILL.md`.
- **MUST NOT** ignore "Uncertain". It is the most common smoking gun for an `@Immutable` contract violated by a mutable sub-field.
- **PREFERRED:** chain a Layout Inspector finding into `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for end-to-end release confirmation.
- **PREFERRED:** when reporting "the fix works", quote the **skip-to-recomposition ratio** (e.g. "100/95" or "0 recompositions across a 30-frame scroll") rather than a raw recomposition count. The ratio survives across debug/release, the raw count does not.

Recomposition is invisible by default; the Layout Inspector + Argument Change Reasons + `@TraceRecomposition` triple is the diagnostic stack. Skippability remains a diagnostic, not a KPI — the goal is "no surprising recompositions on the user-perceived hot path", not "zero recompositions everywhere".

## Verification

- [ ] Layout Inspector "Show recomposition counts" and "Show recomposition skip counts" both toggled on.
- [ ] Counts reset before reproducing the scenario.
- [ ] Suspect composable identified by disproportionate growth in the Recompositions column relative to skip count.
- [ ] Argument Change Reasons panel inspected for the suspect; per-parameter status (Changed / Unchanged / Uncertain / Static / Unknown) named in the report.
- [ ] Status mapped to the correct sibling fix skill; fix applied; baseline (`../../stability/enforcing-stability-in-ci/SKILL.md`) updated if applicable.
- [ ] `@TraceRecomposition` in a release + R8 build confirms the post-fix recomposition count matches the developer's expectation. Debug numbers were directional; this is the final number.

## References

- Ben Trengrove, "Jetpack Compose: Debugging recomposition" — https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Layout Inspector — https://developer.android.com/studio/debug/layout-inspector
- Compose performance overview — https://developer.android.com/develop/ui/compose/performance
- Compose stability — diagnose — https://developer.android.com/develop/ui/compose/performance/stability/diagnose
- Ben Trengrove, "Why you should always test Compose performance in release" — https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Chris Banes, "Composable metrics" — https://chrisbanes.me/posts/composable-metrics/
- skydoves/compose-stability-analyzer (runtime `@TraceRecomposition`) — https://github.com/skydoves/compose-stability-analyzer
- skydoves, "6 Jetpack Compose Guidelines" — https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9

For the upstream stability fix when the status is Uncertain or Unknown, see `../../stability/diagnosing-compose-stability/SKILL.md` and `../../stability/stabilizing-compose-types/SKILL.md`. For lambda-capture causes of Changed, see `../using-strong-skipping-correctly/SKILL.md`. For confirming a fix in a release + R8 build, see `../../measurement/tracing-recompositions-at-runtime/SKILL.md` and `../../measurement/testing-compose-in-release-mode/SKILL.md`. For preventing future regressions, see `../../stability/enforcing-stability-in-ci/SKILL.md`.
