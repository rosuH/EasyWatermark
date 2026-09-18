# E2E map walk (test-map contract §5)

Explore a product path, then promote it if it is repeatable. This is not a PR gate.

After a product change, the **required** loop is [`eval/README.md`](../../eval/README.md) (select → 1-to-1 scripts → heuristic → report). This file is only explore → promote.

Map: [`docs/testmap/map.yaml`](../testmap/map.yaml). Selector: [`scripts/e2e-select.sh`](../../scripts/e2e-select.sh).

## When to walk

- `scripts/e2e-select.sh` points here (matched edges lack L1/L2, or every platform is `drive: none`).
- A new edge or feature has no scripted case yet.
- You changed a system seam (picker, share, FileDialog, permission) that L1 cannot reach.

## Protocol

1. Read `docs/testmap/map.yaml`.
2. Select affected edges via `owners[]` (or take the list from `e2e-select.sh`).
3. Order a path along connected `from` → `to` (start at `launch` or `system` when you can).
4. For each edge, read `platforms.*.drive`:
   - `real` / `seam` — drive it with the platform driver below.
   - `none` — visual / manual verification only. Do not invent a click engine.
5. Record what you saw. If the path is repeatable, promote (below).

## Per-platform drivers

These already exist. Reuse them.

### Android

- `android emulator list` / `android emulator start` (boot `-no-window` if you will not interact). The local console may attach a ready phone/emulator or boot an AVD the same way.
- Debug APK: `./gradlew :app:assembleDebug` — applicationId `me.rosuh.easywatermark.debug` (installs beside production).
- Prefer `android layout` (JSON UI tree) over screenshots to find elements.
- Gestures: `adb shell input tap` / `swipe`.
- Verify with `android screenshot` (look at the pixels).
- Share-in seam: `adb shell am start -a android.intent.action.SEND` (and the usual `SEND` extras / image URI). Picker grid cells are not automatable — do not claim `drive: real`.

### iOS

- Simulator: `xcrun simctl`. The local console (`scripts/e2e-console.sh`) may attach or boot a Simulator / physical iPhone and run `PickerFlowUITests`.
- Fixture seam: `-uiTestFixtureImage 1` (see `iosAppUITests/PickerFlowUITests.swift`). Real PHPicker cells are not addressable on the current toolchain.
- Product `testTag`s surface as accessibility identifiers.
- Screenshots: `xcrun simctl io booted screenshot <path>`.
- Scripted suite (local, not a PR gate):

```
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=<udid>' \
  -only-testing:iosAppUITests/PickerFlowUITests test
```

### Desktop

Window + seam (no FileDialog):

```
./gradlew :desktopApp:run -PewmAutoOpen=<abs image path> -PewmW=<dp> -PewmH=<dp> \
  -PewmForceMarkMode=... -PewmForceText=... -PewmOpenSheet=...
```

Headless spine (decode → watermark → save):

```
./gradlew :desktopApp:run --args='--headless'
```

Verify by screenshot. `-PewmAutoOpen` is a **seam**, not `real`.

### Standing repo rules

- Warn before sustained emulator + build load (this machine has frozen input devices under that load).
- Do not shut down already-live Android or iOS simulators used for migration work unless the owner orders it.
- `./gradlew --stop` when the heavy run is done.
- Verify renders by **viewing** screenshots, not byte sizes (ADR-0010).

## Promotion

A walk that verifies a path repeatably should become a scripted L2 case (XCUITest, UI Automator / `ProductJourneys`, or Desktop headless / `ewm*` props). Then:

1. Add `{layer: L2, ref: Class.method, platform?: ...}` to that edge’s `cases[]` in `map.yaml`.
2. Run `python3 scripts/generate_testmap.py`.
3. `TestMapGuardTest` (`:shared:desktopTest`) checks the ref token exists in repo source.

Do not promote a one-off visual look to L2.

## Honesty

- A seam-driven walk must not be recorded as `drive: real`.
- Edges that stay `drive: none` (real picker cells, system share, AWT FileDialog, permission prompts) remain agent/manual forever. Do not invent a framework to “fix” them.
