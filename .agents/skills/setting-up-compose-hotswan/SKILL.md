---
name: setting-up-compose-hotswan
description: Use this skill to install and verify Compose HotSwan end to end so a developer goes from zero to working sub-second hot reload on a real device or emulator in one session. Covers the JetBrains IDE plugin install, the `com.github.skydoves.compose.hotswan.compiler` Gradle plugin wiring, the canonical `hotSwanCompiler { enabled = true; debugOnly = true }` DSL block, the HotSwan tool window flow, and the first save-to-reload verification. Trigger when the user mentions HotSwan, Compose hot reload, instant UI update, "save and see", "no rebuild", live edit on device, fast Compose iteration, or already has the JetBrains plugin installed and asks how to apply the Gradle compiler plugin.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - hot-reload
  - hotswan
  - gradle-plugin
  - intellij-plugin
  - iteration
  - developer-experience
---

# Setting Up Compose HotSwan: zero to sub-second reload on a real device

Compose HotSwan is a JetBrains IDE plugin plus a Gradle compiler plugin that swaps changed Kotlin classes into a running Compose app on a real device or emulator in under one second, preserving navigation, scroll, and `remember` state. This skill installs both pieces, wires the canonical Gradle DSL with `debugOnly = true`, and verifies the first save-to-reload round-trip. Sibling skills cover what does and does not hot-reload (`../understanding-hot-reload-limits/SKILL.md`), state preservation across reloads, and the AI-driven iteration loop.

> Versions verified at the time of authoring. Confirm against the current release notes for newer minors.

## When to use this skill

- The developer mentions HotSwan, hot reload, instant UI update, "save and see", "no rebuild", live edit, or fast Compose iteration on device.
- The developer has the HotSwan JetBrains Marketplace plugin installed and asks how to apply the Gradle compiler plugin.
- A team is onboarding a new project to HotSwan and wants the canonical `libs.versions.toml` + root build + app build wiring.
- The developer just installed the IDE plugin and the tool window status is stuck before `WATCHING`.

## When NOT to use this skill

- The question is about JetBrains' own Live Edit or Apply Changes features. HotSwan and those are separate products with different boundaries; do not conflate.
- The question is about Compose Preview rendering inside the IDE. HotSwan targets the running app on a real device or emulator, not preview canvases.
- The question is about which changes hot-reload vs force a rebuild. See `../understanding-hot-reload-limits/SKILL.md`.
- The question is about preserving complex state across reloads. See the state-preservation sibling skill (out of scope here).

## Prerequisites

- Android Studio 2024.3+ or IntelliJ IDEA 2024.3+.
- Kotlin 2.0+ with the `org.jetbrains.kotlin.plugin.compose` Gradle plugin already applied on the Compose modules.
- Gradle 8+.
- A real device or emulator with USB debugging enabled and visible to `adb devices`.
- The HotSwan IDE plugin installed from JetBrains Marketplace (https://plugins.jetbrains.com/plugin/30551-compose-hotswan/).

## Workflow

1. **Declare the plugin in `libs.versions.toml`** so the version pins centrally:

   ```toml
   [plugins]
   hotswan-compiler = { id = "com.github.skydoves.compose.hotswan.compiler", version = "1.2.10" }
   ```

   Pin `1.2.10` (latest verified at the time of authoring). Check the [HotSwan releases page](https://hotswan.dev/docs/releases) for newer stable versions before adopting; HotSwan iterates frequently and a newer minor often ships compatibility for a newer Compose runtime. The plugin id is `com.github.skydoves.compose.hotswan.compiler` exactly. Do not swap the `com.github` prefix for any other vendor namespace; HotSwan is published under `com.github.skydoves`.

2. **Apply at the root `build.gradle.kts` with `apply false`** so the plugin is resolved once but only attaches to modules that opt in:

   ```kotlin
   plugins {
       alias(libs.plugins.hotswan.compiler) apply false
   }
   ```

3. **Apply in each Compose app module's `build.gradle.kts`**:

   ```kotlin
   plugins {
       alias(libs.plugins.android.application)
       alias(libs.plugins.kotlin.android)
       alias(libs.plugins.kotlin.compose)
       alias(libs.plugins.hotswan.compiler)
   }
   ```

4. **Configure the canonical DSL block** in the same module file. The capital `S` in `hotSwanCompiler` is part of the identifier:

   ```kotlin
   hotSwanCompiler {
       enabled = true
       debugOnly = true
   }
   ```

   `debugOnly = true` is the default and the rule for production. Release builds MUST NOT carry the HotSwan transformations.

5. **Sync Gradle.** The build should resolve without errors. Confirm the plugin attached by inspecting the build's plugin list (`./gradlew :app:buildEnvironment` or the IDE Gradle tool window).

6. **Open the HotSwan tool window** via `View -> Tool Windows -> HotSwan`. Click `Start`. The status indicator should change to `READY`.

7. **Run the app normally** with the `Run` action. Once the app process connects, the tool window status moves from `READY` to `WATCHING`.

8. **Make a body-only edit and save.** Change the color or text inside one composable function body, then save the file. The change should appear on device in under one second; navigation, scroll position, and `remember` values stay intact.

9. **If the change does not appear, fall through to the troubleshooting checklist below** before changing any Gradle configuration.

## Patterns

### Pattern: configure with debugOnly true (production safety)

```kotlin
// WRONG
hotSwanCompiler {
    enabled = true
}
// WRONG because: omitting `debugOnly = true` allows the HotSwan transformations to attach to release
// builds. They are diagnostic-only and MUST NOT ship in production.
```

```kotlin
// RIGHT
hotSwanCompiler {
    enabled = true
    debugOnly = true
}
```

### Pattern: respect the capital S in the DSL block name

```kotlin
// WRONG
hotswanCompiler {
    enabled = true
    debugOnly = true
}
// WRONG because: the extension is hotSwanCompiler with a capital S in the middle. Lowercase will
// not resolve and Gradle reports an unknown extension.
```

```kotlin
// RIGHT
hotSwanCompiler {
    enabled = true
    debugOnly = true
}
```

### Pattern: apply at root with apply false, in modules with alias(...)

```kotlin
// WRONG (root build.gradle.kts)
plugins {
    alias(libs.plugins.hotswan.compiler)
}
// WRONG because: applying at the root project triggers the compiler plugin everywhere, including
// modules without Compose. Use apply false at the root and alias(...) inside each Compose module.
```

```kotlin
// RIGHT (root build.gradle.kts)
plugins {
    alias(libs.plugins.hotswan.compiler) apply false
}
```

```kotlin
// RIGHT (app/build.gradle.kts; Compose module)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hotswan.compiler)
}

hotSwanCompiler {
    enabled = true
    debugOnly = true
}
```

### Pattern: do not depend on HotSwan at the source level

```kotlin
// WRONG
import com.github.skydoves.compose.hotswan.runtime.HotReloadable

@HotReloadable
@Composable
fun Greeting() { Text("Hello") }
// WRONG because: HotSwan is a build-time and IDE-time tool. Production code must not import
// HotSwan runtime classes or annotations; the Gradle plugin attaches transformations during
// compilation without requiring source-level coupling.
```

```kotlin
// RIGHT
@Composable
fun Greeting() { Text("Hello") }
```

### Pattern: canonical three-file setup (paste-ready)

```toml
# gradle/libs.versions.toml
[plugins]
hotswan-compiler = { id = "com.github.skydoves.compose.hotswan.compiler", version = "1.2.10" }
```

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hotswan.compiler) apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hotswan.compiler)
}

hotSwanCompiler {
    enabled = true
    debugOnly = true
}
```

## Troubleshooting

- **Status stuck at `READY` after `Run`.** The device is not connected. Run `adb devices` and confirm the device or emulator is listed; reconnect USB or restart the emulator.
- **Status `WATCHING` but no reload on save.** The save was a structural change (parameter, constructor, interface, inline function, new resource id). Cross-link `../understanding-hot-reload-limits/SKILL.md` to identify the boundary.
- **Reload happens but state is lost.** The change cascaded to a tier-2 (composition reset) or tier-3 (`Activity.recreate()`) recomposition. Cross-link the state-preservation sibling skill once available.
- **The `hotSwanCompiler { ... }` block does not resolve.** Gradle did not sync after adding the plugin. Re-sync; verify Kotlin 2.0+ is the active Kotlin version on the module; verify the alias matches the entry in `libs.versions.toml`.

## Mandatory rules

- **MUST** set `debugOnly = true`. HotSwan transformations are diagnostic-only and MUST NOT ship in release builds.
- **MUST** apply the Gradle plugin only on Compose modules. Apply at the root project with `apply false` so non-Compose modules are not transformed.
- **MUST NOT** depend on HotSwan classes at the source level. No annotations, no runtime imports. HotSwan is a build-time and IDE-time tool; production code stays pure.
- **MUST NOT** mix HotSwan with a stale Compose Compiler. Verify `org.jetbrains.kotlin.plugin.compose` is applied on Kotlin 2.0+ before adding HotSwan.
- **MUST** pin the HotSwan version in `libs.versions.toml` so all Compose modules in the project use a single version. The latest verified version is `1.2.10`; check [hotswan.dev/docs/releases](https://hotswan.dev/docs/releases) for newer stable releases before pinning.
- **PREFERRED:** combine HotSwan with the AI iteration loop sibling skill (`../iterating-with-ai-and-mcp/SKILL.md`) once the developer is comfortable with manual hot reload and wants Claude to drive the edit-reload-screenshot cycle via MCP.

## Verification

- [ ] `./gradlew :app:buildEnvironment` lists the `com.github.skydoves.compose.hotswan.compiler` plugin among applied plugins.
- [ ] The HotSwan tool window status reads `WATCHING` after the app launches with `Run`.
- [ ] Editing a `Text` color inside a composable and saving the file changes the color on device within one second.
- [ ] `remember` values, scroll position, and the navigation back stack survive the change.
- [ ] A release build (`./gradlew :app:assembleRelease`) does not include HotSwan transformations. Verify by confirming `debugOnly = true` is set, or by inspecting the release dex with `dexdump` for the absence of HotSwan-injected helper classes.
- [ ] No production source file in the module imports a `com.github.skydoves.compose.hotswan.*` symbol (`grep -R "com.github.skydoves.compose.hotswan" src/main/` returns zero hits).

## References

- HotSwan JetBrains Marketplace plugin: https://plugins.jetbrains.com/plugin/30551-compose-hotswan/
- HotSwan releases (canonical source for the latest stable version): https://hotswan.dev/docs/releases
- HotSwan documentation portal (skydoves/compose-hotswan-web): https://github.com/skydoves/compose-hotswan-web
- HotSwan Gradle configuration docs: https://github.com/skydoves/compose-hotswan-web (under `/docs/gradle-configuration`)
- Compose Compiler Gradle plugin (Kotlin 2.0+): https://developer.android.com/jetpack/androidx/releases/compose-compiler
- Sibling skill `../understanding-hot-reload-limits/SKILL.md` for the boundary between hot-reloadable and rebuild-forcing changes.
