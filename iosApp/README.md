# iosApp — EasyWatermark iOS app (C5)

The first iOS application target for EasyWatermark. It is no longer just a build-only shell:

- **S4d-55:** `:shared:iosSimulatorArm64Test` ran on the installed iOS 27.0 simulator and passed 41/41;
  `iosApp` built, installed, launched, and executed shared Kotlin/Native code live.
- **S4d-58:** XCUITest proved the render/export UI through a DEBUG-only fixture seam:
  fixture image -> real `WatermarkWorkflow` / `IosWatermarkRenderBridge` -> watermarked preview ->
  Save to Photos -> Share. The hardened run passed 2/0.
- **Still open:** real PHPicker grid-cell selection. S4d-57 proved XCUITest can open the out-of-process
  picker, but its cells are not addressable on Xcode-27-beta / iOS-27. The S4d-58 fixture seam bypasses
  only that blocked selection step; this is a system-UI automation limitation, not a product failure.

## What it contains

- `iosApp/iOSApp.swift` — SwiftUI `@main` app entry.
- `iosApp/ContentView.swift` (C5.4 / S4d-58) — a SwiftUI `PhotosPicker` workflow: pick a photo -> load
  encoded bytes -> render via `WatermarkWorkflow` -> show the watermarked PNG with `UIImage(data:)`.
  Also owns the DEBUG-only `-uiTestFixtureImage` seam used by XCUITest to bypass only PHPicker cell
  selection while still exercising the real render path.
- `iosApp/WatermarkWorkflow.swift` (C5.4) — `@MainActor ObservableObject` that drives the
  `IosWatermarkRenderBridge.renderWatermarkedPng(...)` path off the main thread and surfaces
  idle/rendering/success/error state.
- `iosApp/ImageExport.swift` (C5.4 / S4d-29) — writes the rendered PNG to a temp file for `ShareLink`
  and saves the exact PNG bytes to Photos with add-only authorization.
- `iosApp/KotlinInterop.swift` (C5.4 / S4d-32) — `Data` <-> `KotlinByteArray` bridging through the
  iosMain `IosByteArrayInterop` memcpy bridge; no Swift per-byte copy loop.
- `iosApp/Resources/Fonts/` (C5.2) — the two Noto faces + OFL licenses, added to **Copy Bundle
  Resources** (they flatten to the `.app` root so `NSBundle.pathForResource(name, type)` finds them).
- `iosAppUITests/PickerFlowUITests.swift` (S4d-57/S4d-58) — XCUITest coverage for opening PHPicker and
  proving the fixture render/export path.
- `iosApp.xcodeproj` — a minimal single-target Xcode project with a shared `iosApp` scheme.

## iOS workflow

Normal app flow:

`ContentView` -> `PhotosPicker(selection:matching:.images)` -> `loadTransferable(type: Data.self)` ->
`WatermarkWorkflow.render(imageData:)`. The workflow calls
`IosWatermarkRenderBridge.renderWatermarkedPng(...)`, which wraps bundled-font loading,
`IosWatermarkRenderer.composeOverImage(...)` (decode via `IosImageDecoder`, Skia bakes EXIF per S4d-23),
and PNG encode behind a Swift-catchable `@Throws` boundary. The returned Kotlin `ByteArray` is converted
to Swift `Data` for `UIImage(data:)`. `PhotosUI`/`UIKit` are system frameworks (no new dependency). The
render runs on a detached task.

Runtime proof:

- S4d-55 ran the shared iOS suite on the iOS 27.0 simulator (41/41) and launched the app with a live
  `:shared` witness.
- S4d-58 launched the app with `-uiTestFixtureImage 1`. That DEBUG-only seam generates a deterministic
  in-memory PNG and feeds it into the same `WatermarkWorkflow.render(imageData:)` path. It does **not**
  fake the preview or export result; it bypasses only the PHPicker grid-cell selection that XCUITest
  cannot address on the beta toolchain.

### Export (C5.4 / S4d-29)

Once a watermarked PNG exists (`workflow.resultPNG`), `ContentView` shows an export bar:

- **Share** — `ShareLink(item: url)` over a temp `.png` (`ImageExport.writeTemporaryPNG`). The system
  share sheet then offers Share / Save Image / Save to Files / AirDrop.
- **Save to Photos** — `ImageExport.saveToPhotos(_:)` requests add-only authorization and writes the
  exact PNG bytes via `PHPhotoLibrary` / `PHAssetCreationRequest.addResource(with: .photo, data:)`
  (no re-encode). Needs `NSPhotoLibraryAddUsageDescription`, set as an `INFOPLIST_KEY_*` build setting
  (the project uses `GENERATE_INFOPLIST_FILE`, so there is no hand-written Info.plist).

`Photos`/`PhotosUI`/`UIKit`/`SwiftUI` are system frameworks auto-linked from `import` — **no new
dependency** and no Frameworks-phase edit. S4d-58 proved both buttons at runtime through XCUITest:
Save reaches "Saved to Photos" and Share presents the system share sheet.

## Watermark fonts (ADR-0025)

Production Text watermarks use the **system default** face (`FontFamily.Default` / platform resolver).
Multi-MB Noto Latin+CJK files are **not** packaged in the iOS app bundle (removed with ADR-0025).
Test-only Noto may still exist under `shared`/`app` **test** source sets for goldens — not in release.

## How `:shared` is wired in

1. `shared/build.gradle.kts` declares a dynamic `Shared` framework on `iosArm64()` /
   `iosSimulatorArm64()`.
2. A **"Build Shared.framework (Kotlin/Native)"** run-script build phase (runs before *Sources*)
   calls:
   ```sh
   cd "$SRCROOT/.."
   ./gradlew :shared:embedAndSignAppleFrameworkForXcode --max-workers=8
   ```
   `embedAndSignAppleFrameworkForXcode` reads Xcode's `CONFIGURATION` / `SDK_NAME` / `ARCHS` env and
   stages the matching framework variant into
   `shared/build/xcode-frameworks/$CONFIGURATION/$SDK_NAME/`.
3. `FRAMEWORK_SEARCH_PATHS` points at that directory and `OTHER_LDFLAGS` adds `-framework Shared`.

The run-script needs `JAVA_HOME` (a JDK 17) on the environment that invokes the build, the same as any
other Gradle task in this repo. Set `EW_SKIP_KOTLIN_FRAMEWORK=1` to skip the Gradle step when the
framework is already staged (useful for repeated build-only checks).

## Build it

A simulator runtime is **not** required to compile/link; only the simulator SDK is. From the repo root:

```sh
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## Run the UI proof

Use a booted simulator. S4d-58 used iPhone 17 Pro / iOS 27.0
(`CF9CE125-D6B2-4D40-B634-56C5E5B65CF4`):

```sh
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=<simulator-udid>' \
  -derivedDataPath <scratch>/DerivedDataUITest \
  -resultBundlePath <scratch>/s4d58.xcresult \
  CODE_SIGNING_ALLOWED=NO \
  test
```

Expected S4d-58 gate:

- `testFixtureRenderPreviewAndExport` passes: fixture -> watermarked preview -> Save to Photos -> Share.
- `testPhotosPickerOpens` passes: PHPicker opens.
- Real PHPicker grid-cell selection is **not** asserted; close that later on a non-beta toolchain or with
  an external coordinate/UI driver.

The Xcode pre-Sources phase invokes Gradle through
`:shared:embedAndSignAppleFrameworkForXcode --max-workers=8`. Stop Gradle daemons after heavy runs:

```sh
./gradlew --stop
```
