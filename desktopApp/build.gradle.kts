import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id(libs.plugins.kotlin.jvm.get().pluginId)
    // S4d-121: Compose Desktop for the minimal window. Existing catalog plugin aliases + versions only
    // (`:shared` already applies both); NO version bump. Desktop-target-only — does not affect Android/iOS.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * `:desktopApp` — the Desktop (JVM) entry point for the Compose Multiplatform target (plan C4).
 * Today it is a minimal runnable that exercises the `:shared` commonMain engine core on the
 * desktop platform, proving the same KMP code Android uses also runs here. The real editor UI
 * (Compose Desktop `Window` + the swapped commonMain renderer) is grown in here during C4/C2b.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    // S4d-80: `:shared` exposes `createUserConfigDataStore`/`UserConfigRepository` whose public types
    // (`DataStore<Preferences>`, `Flow`, suspend) come from datastore + coroutines, declared
    // `implementation` in `:shared` so they do not transit. A consumer that uses those APIs must
    // declare them itself (as `:app` does). Existing catalog aliases only, no new versions.
    implementation(libs.kotlin.coroutine.core)
    implementation(libs.datastore.preference)
    // S4d-143a: `:shared` exposes the commonMain Room API (`AppDatabase`/`buildTemplateDatabase`) whose
    // supertype `androidx.room.RoomDatabase` comes from `room-runtime`, declared `implementation` in
    // `:shared` so it does not transit. The templates witness in `Main.kt` consumes that API, so `:desktopApp`
    // must declare it itself — the same consumer-classpath pattern as coroutines/DataStore above (and as
    // `:app`). Existing catalog alias only; NO new library/version, and NO sqlite native payload (the bundled
    // SQLite driver stays the `:shared` desktopMain-only `sqlite-bundled`, S4d-142).
    implementation(libs.room.runtime)
    // S4d-121: Compose Desktop UI + windowing (Skiko backend) for the minimal window. Same version the
    // `:shared` desktopMain already uses (composeMultiplatform 1.12.0-rc01) — no version bump, no catalog change.
    implementation(compose.desktop.currentOs)
    // S4d-237/S4d-360: Material3 for DesktopWindow. Explicit JetBrains Material3 1.12.0-alpha03
    // (matches :shared; avoids deprecated compose.material3 → 1.9.0 foundation skew).
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
}

// S4d-163 / J3 (issue 13 §J3): packaging honesty.
// Proven on host/CI: `createDistributable` → **unsigned** app image (macOS .app / Linux image /
// Windows dir) with bundled JRE. That alone is **not** release-ready.
//
// Intended signed release formats (residual without owner secrets + matching runners):
// - macOS: DMG or PKG + Developer ID signing + notarization
// - Windows: MSI or EXE + Authenticode
// - Linux: DEB and/or AppImage (or RPM) as chosen
//
// `targetFormats` documents the package* tasks Compose Desktop can generate when run on the
// matching OS. Non-host formats are residual on a single-OS CI runner.
compose.desktop {
    application {
        mainClass = "me.rosuh.easywatermark.desktop.MainKt"
        // ADR-0026 E2E: optional -PewmAutoOpen / -PewmW / -PewmH → app JVM system properties.
        // Production `run` / distributable omit these properties (no auto-import, default window size).
        // Accept both Pewm* (task brief) and ewm* spellings.
        fun prop(vararg names: String): String? =
            names.firstNotNullOfOrNull { providers.gradleProperty(it).orNull }
        listOfNotNull(
            prop("EwmAutoOpen", "ewmAutoOpen")?.let { "-Dewm.desktop.autoOpen=$it" },
            prop("EwmW", "ewmW")?.let { "-Dewm.desktop.widthDp=$it" },
            prop("EwmH", "ewmH")?.let { "-Dewm.desktop.heightDp=$it" },
            prop("EwmInspectorTab", "ewmInspectorTab")?.let { "-Dewm.desktop.inspectorTab=$it" },
            prop("EwmForceMarkMode", "ewmForceMarkMode")?.let { "-Dewm.desktop.forceMarkMode=$it" },
            prop("EwmForceText", "ewmForceText")?.let { "-Dewm.desktop.forceText=$it" },
            prop("EwmOpenSheet", "ewmOpenSheet")?.let { "-Dewm.desktop.openSheet=$it" },
        ).forEach { jvmArgs += it }
        nativeDistributions {
            packageName = "EasyWatermark"
            // S4d-175: single-sourced from the Android app version (buildSrc Apps.versionName), shared with :app.
            packageVersion = Apps.versionName
            description = "EasyWatermark — offline photo watermarking (unsigned createDistributable ≠ release)"
            // Skiko/AWT and other deps touch sun.misc.Unsafe; without jdk.unsupported the
            // packaged runtime image crashes at launch with NoClassDefFoundError: sun/misc/Unsafe.
            modules("jdk.unsupported")
            // J3: declared installer intent; signing/notarization residual (see evidence/j3/matrix.md).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        }
    }
}
