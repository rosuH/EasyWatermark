This guide helps resolve common compilation, import, and sandbox-related errors encountered during the Play Engage SDK integration.

## 1. Ensure sandbox compilation safety for agents

When executing compilations in sandboxed or ephemeral workspaces, such as evaluation environments or automated presubmits, you must follow these rules to prevent container crashes and lockups:

- **Use full compilation** : Run `./gradlew compileDebugUnitTestSources --no-daemon` or `./gradlew assembleDebug --no-daemon`.
- **Don't use compilation shortcuts** : Avoid commands like `./gradlew :app:compileDebugKotlin` or other compile-only tasks. These shortcuts bypass manifest merging and resource packaging, which can hide critical errors like namespace conflicts or XML syntax errors.
- **Don't run full tests or builds** : Don't run `./gradlew test` or `./gradlew build`. These tasks trigger heavy testing environments (requiring emulators) and run full builds, which easily crash the FUSE filesystem mounts and exhaust sandbox CPU and RAM.
- **Always use --no-daemon** : Always append `--no-daemon` to prevent background JVM processes from leaking and locking files in the sandbox.

## 2. Resolve missing imports

If you're upgrading the SDK version, for example, to 1.6.0, or if you get `Unresolved reference` errors during the initial compilation, make sure you've added the correct import statements:

| Class name | Package or import statement | Note |
|---|---|---|
| `ImageTheme` | `import com.google.android.engage.common.datamodel.ImageTheme` |   |
| `PlatformSpecificUri` | `import com.google.android.engage.common.datamodel.PlatformSpecificUri` |   |
| `DisplayTimeWindow` | `import com.google.android.engage.common.datamodel.DisplayTimeWindow` |   |
| `RatingSystem` | `import com.google.android.engage.video.datamodel.RatingSystem` | Note: It's under `video.datamodel`, not `common.datamodel`. |

## 3. Resolve namespace conflicts

You might receive an error message like this one:

    Namespace 'com.google.android.engage' is used in multiple modules and/or libraries: com.google.android.engage:engage-core:1.6.0, com.google.android.engage:engage-tv:1.1.0

This conflict occurs because both packages declare the same namespace.
\* **Resolution** : Remove the explicit `engage-core` dependency from your `build.gradle` or `build.gradle.kts` and keep *only* `engage-tv`. The `engage-tv` library transitively pulls in the compatible version of `engage-core` automatically.

## 4. Resolve sandbox-specific build and test failures

When compiling or running tests in the evaluation sandbox (especially for large projects like Now in Android), you might encounter environment-specific failures:

- **Jlink error with Android SDK 36** : If the build fails during Java compilation with a `jlink` error in `JdkImageTransform` for `android-36`, force Gradle to use JDK 17 by prepending `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` to the Gradle command.
- **Missing ANDROID_HOME** : If lint or test tasks fail complaining about missing SDK, explicitly set the environment variable `ANDROID_HOME=/usr/local/google/home/shashvatgupta/Android/Sdk` (or the corresponding path on the workstation).
- **Roborazzi screenshot mismatches** : Headless or sandbox container rendering might differ from local golden images, causing Roborazzi screenshot test assertions to fail. Bypass these visual checks by appending `-Proborazzi.test.verify=false` to your test command.
- **Robolectric SDK 36 unsupported** : If screenshot tests fail with `java.lang.UnsupportedOperationException` in `DefaultSdkProvider` due to experimental SDK 36, update the `@Config` annotation in the failing test files to pin the SDK to 34 (e.g., `@Config(..., sdk = [34])`).