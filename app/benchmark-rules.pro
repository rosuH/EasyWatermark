# H1 — ProGuard/R8 rules for the app `benchmark` buildType.
#
# The `benchmark` type initWith(release): minify + shrink are ON so measurement
# and Baseline Profile generation match near-release code shape (official guidance:
# non-debuggable, optimized app under test). This file is appended via
# proguardFiles("benchmark-rules.pro") in app/build.gradle.kts.
#
# Keep line numbers for readable traces during Macrobenchmark / ProfileInstaller
# without disabling obfuscation entirely (still release-like).
-keepattributes SourceFile,LineNumberTable

# ProfileInstaller / baseline profile install path must stay resolvable.
-keep class androidx.profileinstaller.** { *; }
-keep class androidx.profileinstaller.ProfileInstallerInitializer { *; }

# Compose runtime reflective entry points used during cold start + first editor frame.
-keep class androidx.compose.runtime.** { *; }
