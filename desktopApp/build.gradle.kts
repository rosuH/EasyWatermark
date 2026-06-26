plugins {
    id(libs.plugins.kotlin.jvm.get().pluginId)
    application
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
}

application {
    mainClass.set("me.rosuh.easywatermark.desktop.MainKt")
}
