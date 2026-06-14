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
}

application {
    mainClass.set("me.rosuh.easywatermark.desktop.MainKt")
}
