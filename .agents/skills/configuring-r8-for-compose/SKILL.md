---
name: configuring-r8-for-compose
description: Use this skill to configure R8 correctly for a Jetpack Compose application — full mode by default, `proguard-android-optimize.txt`, resource shrinking on, and minimal keep rules because Compose ships consumer ProGuard rules. Covers AGP 8.0+ R8 full mode default, R8's Compose-aware optimizations (lambda grouping, `sourceInformation()` stripping, composable arg constant-folding, `ComposerImpl` devirtualization), legitimate keep needs (`@Serializable`, Hilt entry points, reflective `Saver`s), and the AGP 8.x missing-rule reporter / R8 retrace. Cited gain is roughly 75 percent startup and 60 percent frame-render improvement debug-to-release. Use when setting up a new Compose app, when a PR adds an over-broad keep like `-keep class androidx.compose.** { *; }`, when a release build crashes after enabling minification, when APK size needs reduction, or when first enabling minification.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - r8
  - proguard
  - keep-rules
  - apk-size
  - minification
  - shrinking
  - obfuscation
  - release-build
---

# Configuring R8 for Compose — Trust Consumer Rules, Avoid Blanket Keeps

R8 is the only supported shrinker — ProGuard is deprecated. Compose ships consumer ProGuard rules, so most apps need **no** Compose-specific keep rules. The big modern wins come from R8 full mode (default since AGP 8.0): lambda grouping, `sourceInformation()` stripping, constant-folding of composable args, and `ComposerImpl` devirtualization. This skill teaches Claude to set R8 up minimally and refuse over-broad keeps that defeat those wins.

## When to use this skill

- Setting up a new Compose app's release variant for the first time.
- A PR adds `-keep class androidx.compose.** { *; }` or any other Compose-wide keep — push back and apply this skill.
- A release build crashes after enabling minification — the cause is almost always a missing keep on a reflective consumer, not on Compose itself.
- APK size review surfaces a large `classes.dex` and the developer wants to know why.
- The first time `isMinifyEnabled = true` is being flipped on, before any release benchmark is run.
- Cross-link from `../../measurement/testing-compose-in-release-mode/SKILL.md` whenever release measurement is being set up.

## When NOT to use this skill

- The app is not yet ready to ship a release variant — wait until perf and crash budgets are in scope.
- The developer is debugging a code-level bug (logic error, race, wrong state). R8 issues manifest as `ClassNotFoundException`, `NoSuchMethodError`, or empty reflection results — not behavioral bugs.
- The release variant has been measured and is fine — do not pre-emptively add keep rules.
- The perf concern is recomposition, layout phase, or stability — see the relevant `stability/` or `recomposition/` skills.

## Prerequisites

- AGP 8.0+ for R8 full mode default. If on older AGP, full mode must be explicitly enabled in `gradle.properties` with `android.enableR8.fullMode=true`.
- A buildable release variant (debug-signed is fine while iterating — see `../../measurement/testing-compose-in-release-mode/SKILL.md` for measurement signing).
- Kotlin 2.0+ with the `org.jetbrains.kotlin.plugin.compose` plugin so Compose's bundled consumer rules are wired correctly through dependency resolution.
- Familiarity with the difference between `proguard-android.txt` (no R8 optimizations) and `proguard-android-optimize.txt` (R8 optimizations on).

## What R8 actually does for Compose (since AGP 8.0)

Surface these to the developer when they ask "why bother" or push back against minification:

1. **Lambda grouping.** R8 merges many of Compose's generated lambda classes, reducing dex size and method count.
2. **`sourceInformation()` stripping.** Each composable emits a string of source-position metadata. R8 removes it from release builds — smaller APK and less work per recomposition.
3. **Composable arg constant-folding.** Constants that would have been wrapped by Live Literals in debug are now folded back into `if (changed and 1 == 0)` style checks the recomposer can short-circuit.
4. **`ComposerImpl` devirtualization.** R8 devirtualizes hot calls into Compose's runtime, which is a major contributor to the cited frame-render improvement.
5. **Resource shrinking.** Paired with `isShrinkResources = true`, R8 strips unreachable drawables, strings, and layout XML.

Cited measurement: roughly **75 percent startup gain** and **60 percent frame-render gain** debug to release. Source: Ben Trengrove, "Why should you always test Compose performance in release" (Android Developers Medium).

## Workflow

- [ ] **1. Configure the release buildType.** This is the entire happy-path config — no Compose-specific keeps needed:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

- [ ] **2. Verify the default file name.** The single most common mistake is using `proguard-android.txt` instead of `proguard-android-optimize.txt`. Only the `-optimize` variant enables R8's optimization passes.

- [ ] **3. Trust Compose's bundled consumer rules. DO NOT add Compose-wide keeps.** Each Compose artifact (`androidx.compose.runtime`, `androidx.compose.ui`, `androidx.compose.foundation`, `androidx.compose.material3`, etc.) ships a `consumer-rules.pro` that R8 picks up automatically. The developer's `proguard-rules.pro` should contain **no** lines starting with `-keep class androidx.compose.`.

- [ ] **4. Identify legitimate keep needs from the project.** R8 problems are usually outside Compose:
  - `@Serializable` data classes consumed by `kotlinx.serialization` (the `kotlinx-serialization` Gradle plugin emits the right rules — verify, do not duplicate).
  - Hilt / Dagger entry points and generated DI code (the Hilt plugin emits these — verify).
  - Custom `rememberSaveable` `Saver` objects only when accessed reflectively from outside the module.
  - Any `Class.forName(...)` consumer, JNI binding, or service loader.
  - Public API surfaces of library modules consumed by other apps.

- [ ] **5. Run the AGP 8.x missing-rule reporter to find under-keeps.** Do not add speculative keeps. After a build that fails at runtime in release, AGP writes a report at `app/build/outputs/mapping/release/missing_rules.txt` listing the exact `-keep` directives R8 inferred from runtime failures. Add only those, narrowed to the specific class/method.

- [ ] **6. Use R8 retrace to map crash stacks back to source.** When a release crash arrives, run the modern R8 retrace shipped with the Android command-line tools:

```bash
# Modern R8 retrace (cmdline-tools 7.0+):
$ANDROID_HOME/cmdline-tools/latest/bin/retrace \
    app/build/outputs/mapping/release/mapping.txt < crash.txt
```

Or use the Gradle convenience target wired into AGP:

```bash
./gradlew :app:retraceR8DebuggingArtifact
```

(The legacy ProGuard `retrace.sh` install path under `$ANDROID_HOME/tools/` was removed when SDK Tools 26 was sunset; do not look for it.) Always retrace before diagnosing a release crash — the stack frames are otherwise meaningless.

- [ ] **7. Verify with an APK analysis.** After enabling minification:

```bash
./gradlew :app:assembleRelease
# Then in Android Studio: Build → Analyze APK → app-release.apk
# Compare classes.dex method count and resources.arsc size against the same build with isMinifyEnabled = false.
```

A correctly configured Compose app with no over-broad keeps typically halves the method count vs the un-minified release.

- [ ] **8. Re-run Macrobenchmark to capture the perf gain.** Cross-link `../../measurement/testing-compose-in-release-mode/SKILL.md`. The expected delta vs `CompilationMode.None` against a debug variant is in the 75 percent / 60 percent range cited above.

## Patterns

### Pattern: Over-broad Compose keep

```proguard
# WRONG
-keep class androidx.compose.** { *; }
# WRONG because: Compose's own consumer rules are tighter and correct; a wildcard keep blocks lambda grouping, prevents sourceInformation stripping, kills composable-arg constant folding, defeats ComposerImpl devirtualization, and bloats classes.dex. The cited 75/60 gains evaporate.
```

```proguard
# RIGHT — let Compose's consumer rules handle it.
# (no Compose-specific lines in proguard-rules.pro by default)
```

### Pattern: Wrong default ProGuard file

```kotlin
// WRONG
buildTypes.release {
    isMinifyEnabled = true
    proguardFiles(
        getDefaultProguardFile("proguard-android.txt"),
        "proguard-rules.pro",
    )
}
// WRONG because: proguard-android.txt explicitly skips R8's optimization passes. Lambda grouping, devirtualization, constant folding, sourceInformation stripping all do not run. The release variant builds and installs but performs like a poorly minified app.
```

```kotlin
// RIGHT
buildTypes.release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
    )
}
```

### Pattern: Defensive `kotlin.Metadata` keep

```proguard
# WRONG
-keep class kotlin.Metadata { *; }
# WRONG because: blanket-keeping kotlin.Metadata blocks R8 from shrinking metadata for unreachable Kotlin classes; identify the actual reflective consumer (kotlinx.serialization, Moshi, Gson, Hilt) and add a narrow rule for that consumer instead.
```

```proguard
# RIGHT — narrow to the reflective consumer
# Example: kotlinx.serialization needs Companion access on KSerializer implementations.
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public static **$Companion Companion;
}
```

### Pattern: `-dontobfuscate` for "easier debugging"

```proguard
# WRONG
-dontobfuscate
-dontoptimize
# WRONG because: turning off obfuscation and optimization on the release variant removes most of R8's benefit; the variant being measured is no longer the variant that ships. If readable stacks are needed, ship with R8 on and use retrace + the mapping.txt to deobfuscate.
```

```proguard
# RIGHT — leave R8 on, keep mapping.txt for retrace.
# (no -dontobfuscate / -dontoptimize lines)
# Upload mapping.txt to your crash reporter (Crashlytics, Sentry, etc.) so stacks
# are deobfuscated automatically; or run R8 retrace manually:
#   $ANDROID_HOME/cmdline-tools/latest/bin/retrace mapping.txt < crash.txt
# (or: ./gradlew :app:retraceR8DebuggingArtifact)
```

### Pattern: `@Serializable` keep when the kotlinx-serialization plugin is missing

```proguard
# RIGHT — when using kotlinx.serialization with reflection-based polymorphism
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public static **$Companion Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
# But PREFERRED: apply the kotlinx-serialization Gradle plugin so these rules are emitted automatically.
```

### Pattern: Reflective `rememberSaveable` Saver

```proguard
# WRONG — defensive blanket keep
-keep class **.Saver { *; }
# WRONG because: most Saver implementations are reachable from non-reflective code and are kept automatically; a blanket keep prevents inlining and elimination.
```

```proguard
# RIGHT — only when the Saver is loaded by name reflectively
-keepclassmembers class com.example.feature.MyTypeSaver {
    public static *** INSTANCE;
}
```

## Mandatory rules

- **MUST** use `proguard-android-optimize.txt`, not `proguard-android.txt`. The non-optimize variant disables R8's optimization passes.
- **MUST** keep `isMinifyEnabled = true` AND `isShrinkResources = true` for release.
- **MUST NOT** add Compose-wide keep rules (`-keep class androidx.compose.** { *; }`, `-keep class * extends androidx.compose.runtime.Composable`, etc.). Compose ships correct consumer rules; a blanket keep defeats lambda grouping, `sourceInformation` stripping, constant folding, and `ComposerImpl` devirtualization.
- **MUST NOT** add `-dontobfuscate` or `-dontoptimize` to the release variant for "easier debugging". Measure with R8 on and use R8 retrace + `mapping.txt` for stack readability; debug code paths separately on the debug variant.
- **MUST** rely on AGP 8.x's `missing_rules.txt` reporter to find narrow under-keeps. Do not pre-emptively keep packages.
- **MUST** retrace any release crash with the variant's `mapping.txt` before drawing conclusions about the cause.
- **PREFERRED:** apply the official Gradle plugins for libraries that need keep rules (`kotlinx-serialization`, Hilt, Moshi-codegen). The plugins emit the right rules automatically.
- **PREFERRED:** track APK size in CI with `Build → Analyze APK` or a Gradle plugin so a regression in dex size flags an over-broad keep added by a future PR.

## Verification

- [ ] `proguard-rules.pro` contains zero lines matching `^-keep .* androidx\.compose\.`.
- [ ] `app/build.gradle.kts` release block uses `getDefaultProguardFile("proguard-android-optimize.txt")` exactly.
- [ ] `isMinifyEnabled = true` and `isShrinkResources = true` are both set on the release buildType.
- [ ] `./gradlew :app:assembleRelease` succeeds.
- [ ] APK Analyzer shows the release `classes.dex` method count is meaningfully lower than the same release built with `isMinifyEnabled = false`.
- [ ] Macrobenchmark startup with `CompilationMode.Partial(BaselineProfileMode.Require)` against the release variant is faster than the same benchmark with `CompilationMode.None`.
- [ ] R8 retrace produces a readable stack from a captured release crash using `app/build/outputs/mapping/release/mapping.txt`.
- [ ] If `missing_rules.txt` was generated, only the listed narrow keeps were added — no broadening.

## References

- Android Developers — R8 keep rules overview: https://developer.android.com/topic/performance/app-optimization/keep-rules-overview
- Android Developers — Configure and troubleshoot R8 keep rules (2025): https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html
- Android Developers — Performance overview: https://developer.android.com/develop/ui/compose/performance
- Android Developers — Why test perf in release (Ben Trengrove): https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Android Developers — Baseline Profiles with Compose: https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- Chris Banes — Composable Metrics: https://chrisbanes.me/posts/composable-metrics/
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- skydoves — Baseline Profiles with GetStream: https://getstream.io/blog/android-baseline-profile/
