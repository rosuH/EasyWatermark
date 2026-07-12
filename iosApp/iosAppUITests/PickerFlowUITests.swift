import XCTest

// S4d-58 / C5.3-d — XCUITest proof of the iosApp render + export UI via the DEBUG fixture seam.
//
// Background: S4d-57 proved XCUITest can OPEN the out-of-process PHPicker but cannot address its grid
// photo cells on Xcode-27-beta / iOS-27 (collectionViews empty; scrollViews.images sees only the
// "Limited Access" banner). This suite bypasses ONLY that blocked picker-selection step using the
// `-uiTestFixtureImage` launch argument (ContentView, #if DEBUG), which feeds a deterministic in-memory
// PNG through the REAL WatermarkWorkflow → IosWatermarkRenderBridge render path — then asserts the
// watermarked preview + status and exercises Share / Save-to-Photos.
final class PickerFlowUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// The real proof: fixture image → shared render → watermarked preview → export UI.
    func testFixtureRenderPreviewAndExport() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        attach(app, "01-launched-with-fixture")

        // Render runs on launch via the fixture seam; wait for the real shared CMP preview host.
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30),
                      "Shared CMP watermark preview never appeared — fixture render did not reach the host.")
        let outputActions = app.descendants(matching: .any)["sharedComposeSavedOutputActions"].firstMatch
        XCTAssertTrue(outputActions.waitForExistence(timeout: 5),
                      "Shared CMP saved-output action row never appeared after fixture render.")
        // Product order keeps actions below the editor stack; scroll host first, then host-relative taps
        // (nested Compose buttons often keep a stale non-hittable Y even when labels exist).
        XCTAssertTrue(scrollUntilHittable(outputActions, in: app, timeout: 20),
                      "Shared CMP saved-output action row was not hittable after scroll.")
        attach(app, "02-watermarked-preview")

        let saveLabel = outputActions.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "Save to Photos")).firstMatch
        XCTAssertTrue(saveLabel.waitForExistence(timeout: 5), "Save to Photos label missing in action row.")
        tapSharedOutputAction(labeled: saveLabel, in: outputActions)

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow Access to All Photos", "Allow Full Access", "Allow", "OK", "允许", "好"] {
            let b = springboard.buttons[label].firstMatch
            if b.waitForExistence(timeout: 4) { b.tap(); break }
        }
        // Success slice: Save MUST report "Saved to Photos" and MUST NOT report "Save failed".
        let saved = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'Saved'")).firstMatch
        let saveFailed = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'Save failed'")).firstMatch
        let savedAppeared = saved.waitForExistence(timeout: 15)
        attach(app, "03-after-save")
        XCTAssertFalse(saveFailed.exists, "Save-to-Photos reported 'Save failed': \(saveFailed.label)")
        XCTAssertTrue(savedAppeared, "Save-to-Photos did not reach a 'Saved to Photos' confirmation.")

        // Share — label existence for X, host dy=0.5 for current visible Y → UIKit share sheet.
        XCTAssertTrue(scrollUntilHittable(outputActions, in: app, timeout: 10),
                      "Shared CMP saved-output action row not hittable before Share.")
        let shareLabel = outputActions.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "Share")).firstMatch
        XCTAssertTrue(shareLabel.waitForExistence(timeout: 5), "Share label missing in action row.")
        tapSharedOutputAction(labeled: shareLabel, in: outputActions)
        let shareSheet = app.otherElements["ActivityListView"].firstMatch
        let copyAction = app.buttons["Copy"].firstMatch
        let shareSheetAppeared = shareSheet.waitForExistence(timeout: 10) || copyAction.waitForExistence(timeout: 5)
        attach(app, "04-share-sheet")
        XCTAssertTrue(shareSheetAppeared,
                      "System share sheet did not appear after tapping Share (no ActivityListView / Copy action).")
    }

    /// S4d-329: the normal iOS tile-mode picker is now a shared CMP control that still writes through
    /// WatermarkWorkflow and re-renders the fixture image.
    func testSharedComposeTileModeChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeTileMode"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared tile-mode control did not appear.")

        let repeatChoice = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Repeat")).firstMatch
        XCTAssertTrue(scrollUntilHittable(repeatChoice, in: app, timeout: 10), "Repeat tile-mode choice was not reachable.")
        repeatChoice.tap()
        let repeatSelected = expectation(for: NSPredicate(format: "label == %@", "Tile mode Repeat"), evaluatedWith: control, handler: nil)
        wait(for: [repeatSelected], timeout: 15)

        let single = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Single")).firstMatch
        XCTAssertTrue(scrollUntilHittable(single, in: app, timeout: 10), "Single tile-mode choice was not reachable.")
        single.tap()
        let singleSelected = expectation(for: NSPredicate(format: "label == %@", "Tile mode Single"), evaluatedWith: control, handler: nil)
        wait(for: [singleSelected], timeout: 15)

        // Reload through the existing app entry to prove the Swift workflow write persisted and fed the
        // current mode back into the production CMP host.
        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared tile-mode control did not reappear after reload.")
        let persistedSingle = expectation(for: NSPredicate(format: "label == %@", "Tile mode Single"), evaluatedWith: control, handler: nil)
        wait(for: [persistedSingle], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after tile change.")
        attach(app, "05-shared-compose-tile-single")
    }

    /// S4d-330: the normal iOS text-style picker is now a shared CMP control that still writes through
    /// WatermarkWorkflow and re-renders the fixture image.
    func testSharedComposeTextPaintStyleChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeTextPaintStyle"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-style control did not appear.")

        // Segment child roles are not stable in Compose UIKit after the preceding shared slider hosts.
        // Tap the actual rendered host positions instead of adding a test-only state setter.
        let fill = control.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5))
        fill.tap()
        let fillSelected = expectation(for: NSPredicate(format: "label == %@", "Text style Fill"), evaluatedWith: control, handler: nil)
        wait(for: [fillSelected], timeout: 15)

        let stroke = control.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5))
        stroke.tap()
        let strokeSelected = expectation(for: NSPredicate(format: "label == %@", "Text style Stroke"), evaluatedWith: control, handler: nil)
        wait(for: [strokeSelected], timeout: 15)

        // Reload through the existing app entry to prove the Swift workflow write persisted and fed the
        // current style back into the production CMP host.
        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared text-style control did not reappear after reload.")
        let persistedStroke = expectation(for: NSPredicate(format: "label == %@", "Text style Stroke"), evaluatedWith: control, handler: nil)
        wait(for: [persistedStroke], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after text-style change.")
        attach(app, "06-shared-compose-text-style-stroke")
    }

    /// S4d-331: the normal iOS typeface picker is now a shared CMP control that still writes through
    /// WatermarkWorkflow and re-renders the fixture image.
    func testSharedComposeTextTypefaceChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeTextTypeface"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared typeface control did not appear.")

        // Segment child roles are not stable in Compose UIKit after the preceding shared slider hosts.
        // Tap the actual rendered host positions instead of adding a test-only state setter.
        let normal = control.coordinate(withNormalizedOffset: CGVector(dx: 0.125, dy: 0.5))
        normal.tap()
        let normalSelected = expectation(for: NSPredicate(format: "label == %@", "Typeface Normal"), evaluatedWith: control, handler: nil)
        wait(for: [normalSelected], timeout: 15)

        let bold = control.coordinate(withNormalizedOffset: CGVector(dx: 0.375, dy: 0.5))
        bold.tap()
        let boldSelected = expectation(for: NSPredicate(format: "label == %@", "Typeface Bold"), evaluatedWith: control, handler: nil)
        wait(for: [boldSelected], timeout: 15)

        // Reload through the existing app entry to prove the Swift workflow write persisted and fed the
        // current typeface back into the production CMP host.
        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared typeface control did not reappear after reload.")
        let persistedBold = expectation(for: NSPredicate(format: "label == %@", "Typeface Bold"), evaluatedWith: control, handler: nil)
        wait(for: [persistedBold], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after typeface change.")
        attach(app, "07-shared-compose-typeface-bold")
    }

    /// S4d-332: the normal iOS text-size slider is now a shared CMP control. Compose UIKit does not export
    /// a Slider role on this dependency mix, so the test taps the real track coordinates and proves the
    /// workflow persists the chosen value through a relaunch.
    func testSharedComposeTextSizeChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeTextSize"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-size control did not appear.")

        // The shared host is 72pt high: SliderOption's track occupies the upper portion, with its value
        // text below. Tapping the track is actual Compose pointer input, not a test-only state setter.
        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.25))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.25))
        rightTrack.tap()

        if waitForLabelChange(control, from: initialLabel, timeout: 15) {
            let rightLabel = control.label
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: rightLabel, timeout: 15),
                          "Shared slider did not commit the opposite track position.")
        } else {
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared slider did not commit either track position.")
            let leftLabel = control.label
            rightTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: leftLabel, timeout: 15),
                          "Shared slider did not commit the opposite track position.")
        }
        let selectedLabel = control.label
        XCTAssertTrue(selectedLabel.hasPrefix("Text size "), "Shared slider did not report a persisted text-size label.")

        // Reload through the existing app entry to prove the shared slider's finish callback wrote through
        // WatermarkWorkflow and the persisted value fed back into the production host.
        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared text-size control did not reappear after reload.")
        let persistedSize = expectation(for: NSPredicate(format: "label == %@", selectedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedSize], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after text-size change.")
        attach(app, "08-shared-compose-text-size")
    }

    /// S4d-333: the normal iOS rotation slider is now a shared CMP control. The test taps the real
    /// Compose track and proves the workflow persists its observed value through a relaunch.
    func testSharedComposeWatermarkDegreeChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeWatermarkDegree"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared rotation control did not appear.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.25))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.25))
        rightTrack.tap()

        if waitForLabelChange(control, from: initialLabel, timeout: 15) {
            let rightLabel = control.label
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: rightLabel, timeout: 15),
                          "Shared rotation slider did not commit the opposite track position.")
        } else {
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared rotation slider did not commit either track position.")
            let leftLabel = control.label
            rightTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: leftLabel, timeout: 15),
                          "Shared rotation slider did not commit the opposite track position.")
        }
        let selectedLabel = control.label
        XCTAssertTrue(selectedLabel.hasPrefix("Rotation "), "Shared rotation slider did not report a persisted label.")

        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared rotation control did not reappear after reload.")
        let persistedDegree = expectation(for: NSPredicate(format: "label == %@", selectedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedDegree], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after rotation change.")
        attach(app, "09-shared-compose-rotation")
    }

    /// S4d-334: the normal iOS opacity slider is now a shared CMP control. Alpha persistence stores a
    /// byte, so the relaunch assertion accounts for the established percent-to-byte quantization.
    func testSharedComposeWatermarkAlphaChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeWatermarkAlpha"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared opacity control did not appear.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.25))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.25))
        rightTrack.tap()

        if waitForLabelChange(control, from: initialLabel, timeout: 15) {
            let rightLabel = control.label
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: rightLabel, timeout: 15),
                          "Shared opacity slider did not commit the opposite track position.")
        } else {
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared opacity slider did not commit either track position.")
            let leftLabel = control.label
            rightTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: leftLabel, timeout: 15),
                          "Shared opacity slider did not commit the opposite track position.")
        }

        let selectedLabel = control.label
        XCTAssertTrue(selectedLabel.hasPrefix("Opacity "), "Shared opacity slider did not report a selected value.")
        let selectedPercent = Int(selectedLabel.dropFirst("Opacity ".count).dropLast())
        XCTAssertNotNil(selectedPercent, "Shared opacity slider label did not contain an integer percent.")
        let byte = Int(Float(selectedPercent!) / 100.0 * 255.0)
        let persistedLabel = "Opacity \(Int(Float(byte) / 255.0 * 100.0))%"

        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared opacity control did not reappear after reload.")
        let persistedAlpha = expectation(for: NSPredicate(format: "label == %@", persistedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedAlpha], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after opacity change.")
        attach(app, "10-shared-compose-opacity")
    }

    /// S4d-335: the normal iOS horizontal-gap slider is now a shared CMP control. The test taps the
    /// real Compose track and proves the integer gap persists through a relaunch.
    func testSharedComposeWatermarkHorizontalGapChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeWatermarkHGap"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared horizontal-gap control did not appear.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.25))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.25))
        rightTrack.tap()

        if waitForLabelChange(control, from: initialLabel, timeout: 15) {
            let rightLabel = control.label
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: rightLabel, timeout: 15),
                          "Shared horizontal-gap slider did not commit the opposite track position.")
        } else {
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared horizontal-gap slider did not commit either track position.")
            let leftLabel = control.label
            rightTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: leftLabel, timeout: 15),
                          "Shared horizontal-gap slider did not commit the opposite track position.")
        }
        let selectedLabel = control.label
        XCTAssertTrue(selectedLabel.hasPrefix("Horizontal gap "),
                      "Shared horizontal-gap slider did not report a selected value.")

        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared horizontal-gap control did not reappear after reload.")
        let persistedGap = expectation(for: NSPredicate(format: "label == %@", selectedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedGap], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after horizontal-gap change.")
        attach(app, "11-shared-compose-horizontal-gap")
    }

    /// S4d-336: the normal iOS vertical-gap slider is now a shared CMP control. The test taps the
    /// real Compose track and proves the integer gap persists through a relaunch.
    func testSharedComposeWatermarkVerticalGapChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeWatermarkVGap"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared vertical-gap control did not appear.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.25))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.25))
        rightTrack.tap()

        if waitForLabelChange(control, from: initialLabel, timeout: 15) {
            let rightLabel = control.label
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: rightLabel, timeout: 15),
                          "Shared vertical-gap slider did not commit the opposite track position.")
        } else {
            leftTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared vertical-gap slider did not commit either track position.")
            let leftLabel = control.label
            rightTrack.tap()
            XCTAssertTrue(waitForLabelChange(control, from: leftLabel, timeout: 15),
                          "Shared vertical-gap slider did not commit the opposite track position.")
        }
        let selectedLabel = control.label
        XCTAssertTrue(selectedLabel.hasPrefix("Vertical gap "),
                      "Shared vertical-gap slider did not report a selected value.")

        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared vertical-gap control did not reappear after reload.")
        let persistedGap = expectation(for: NSPredicate(format: "label == %@", selectedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedGap], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after vertical-gap change.")
        attach(app, "12-shared-compose-vertical-gap")
    }

    /// S4d-337: the normal iOS four-preset text-color picker is now a shared CMP palette. The test taps
    /// a real exposed swatch and proves the workflow persists the changed ARGB value through a relaunch.
    func testSharedComposeTextColorChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()

        let control = app.descendants(matching: .any)["sharedComposeTextColor"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-color control did not appear.")

        let initialLabel = control.label
        let blackSwatch = app.descendants(matching: .any)["Text color #FF000000"].firstMatch
        XCTAssertTrue(scrollUntilHittable(blackSwatch, in: app, timeout: 10),
                      "Shared black text-color swatch was not reachable.")
        blackSwatch.tap()
        let selectedLabel: String
        if waitForLabelChange(control, from: initialLabel, timeout: 3) {
            selectedLabel = control.label
        } else {
            // A prior focused test or user state may already be black. Exercise the other real swatch
            // instead of treating an idempotent selection as a product failure.
            let whiteSwatch = app.descendants(matching: .any)["Text color #FFFFFFFF"].firstMatch
            XCTAssertTrue(scrollUntilHittable(whiteSwatch, in: app, timeout: 10),
                          "Shared white text-color swatch was not reachable.")
            whiteSwatch.tap()
            XCTAssertTrue(waitForLabelChange(control, from: initialLabel, timeout: 15),
                          "Shared text-color palette did not commit either available swatch.")
            selectedLabel = control.label
        }

        app.terminate()
        app.launch()
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "Production shared text-color control did not reappear after reload.")
        let persistedColor = expectation(for: NSPredicate(format: "label == %@", selectedLabel), evaluatedWith: control, handler: nil)
        wait(for: [persistedColor], timeout: 20)
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 15), "Fixture render did not remain visible after text-color change.")
        attach(app, "13-shared-compose-text-color")
    }

    /// S4d-340: the production shared CMP launch shell delegates only the system picker presentation.
    /// Grid-cell selection remains the S4d-57 beta-toolchain block.
    func testPhotosPickerOpens() {
        let app = XCUIApplication()
        app.launch()
        let launch = app.descendants(matching: .any)["sharedComposeLaunchScreen"].firstMatch
        XCTAssertTrue(launch.waitForExistence(timeout: 20), "Shared launch screen did not appear")
        attach(app, "09-shared-compose-launch-screen")
        launch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        // The picker is exposed to XCUITest as a scrollView (S4d-57). Assert only that it opens.
        let pickerScroll = app.scrollViews.firstMatch
        XCTAssertTrue(pickerScroll.waitForExistence(timeout: 12),
                      "PhotosPicker did not open from the shared launch screen.")
        attach(app, "10-shared-compose-launch-picker-opened")
        // NOTE: grid-cell SELECTION is NOT asserted — unaddressable on this beta toolchain (S4d-57);
        // the render/export path is proven via the fixture seam in testFixtureRenderPreviewAndExport.
    }

    /// S4d-339: the visible image-watermark option is shared CMP UI, while SwiftUI still presents
    /// the out-of-process PhotosPicker. Grid-cell selection remains the S4d-57 beta-toolchain block.
    func testSharedComposeIconPickerOpens() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        let control = app.descendants(matching: .any)["sharedComposeIconWatermarkOption"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared icon-watermark option did not appear.")
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let pickerScroll = app.scrollViews.firstMatch
        XCTAssertTrue(pickerScroll.waitForExistence(timeout: 12),
                      "PhotosPicker did not open from the shared icon option.")
        attach(app, "14-shared-compose-icon-picker-opened")
    }

    /// S4d-323: normal debug launches must not show the CMP host witnesses. They are test-only
    /// runtime/link proof, enabled only by the `-sharedComposeWitnesses` launch argument.
    func testSharedComposeWitnessesHiddenByDefault() {
        let app = XCUIApplication()
        app.launch()
        let launch = app.descendants(matching: .any)["sharedComposeLaunchScreen"].firstMatch
        XCTAssertTrue(launch.waitForExistence(timeout: 20), "Shared launch screen did not appear")

        for id in [
            "sharedComposeLaunchShellWitness",
            "sharedComposeGalleryShellWitness",
            "sharedComposeAboutShellWitness",
            "sharedComposeEditorShellWitness",
        ] {
            XCTAssertFalse(app.descendants(matching: .any)[id].firstMatch.exists,
                           "\(id) should be hidden unless -sharedComposeWitnesses is present.")
        }
        attach(app, "29-shared-compose-witnesses-hidden-default")
    }

    /// S4d-320: proves the DEBUG-only iOS shared CMP launch-shell witness is embedded in the SwiftUI
    /// surface. This is a host/link/runtime proof only; it does not replace the SwiftUI picker/export UI.
    func testSharedComposeLaunchWitnessVisible() {
        let app = XCUIApplication()
        app.launchArguments += ["-sharedComposeWitnesses", "1", "-sharedComposeWitness", "launch"]
        app.launch()
        let witness = app.descendants(matching: .any)["sharedComposeLaunchShellWitness"].firstMatch
        XCTAssertTrue(scrollUntilHittable(witness, in: app, timeout: 12),
                      "sharedComposeLaunchShellWitness was not reachable in the iOS bring-up surface.")
        attach(app, "30-shared-compose-launch-witness")
    }

    /// S4d-321: proves the DEBUG-only iOS shared CMP gallery-shell witness is embedded in the SwiftUI
    /// surface. This is a host/link/runtime proof only; it does not replace the system PhotosPicker.
    func testSharedComposeGalleryWitnessVisible() {
        let app = XCUIApplication()
        app.launchArguments += ["-sharedComposeWitnesses", "1", "-sharedComposeWitness", "gallery"]
        app.launch()
        let witness = app.descendants(matching: .any)["sharedComposeGalleryShellWitness"].firstMatch
        XCTAssertTrue(scrollUntilHittable(witness, in: app, timeout: 12),
                      "sharedComposeGalleryShellWitness was not reachable in the iOS bring-up surface.")
        attach(app, "31-shared-compose-gallery-witness")
    }

    /// S4d-322: proves the DEBUG-only iOS shared CMP About-shell witness is embedded in the SwiftUI
    /// surface. This is a host/link/runtime proof only; it does not replace production navigation.
    func testSharedComposeAboutWitnessVisible() {
        let app = XCUIApplication()
        app.launchArguments += ["-sharedComposeWitnesses", "1", "-sharedComposeWitness", "about"]
        app.launch()
        let witness = app.descendants(matching: .any)["sharedComposeAboutShellWitness"].firstMatch
        XCTAssertTrue(scrollUntilHittable(witness, in: app, timeout: 12),
                      "sharedComposeAboutShellWitness was not reachable in the iOS bring-up surface.")
        attach(app, "32-shared-compose-about-witness")
    }

    /// S4d-325: proves the DEBUG-only iOS shared CMP editor-shell witness is embedded in the SwiftUI
    /// surface. This is a host/link/runtime proof only; it does not replace the SwiftUI editor controls.
    func testSharedComposeEditorWitnessVisible() {
        let app = XCUIApplication()
        app.launchArguments += ["-sharedComposeWitnesses", "1", "-sharedComposeWitness", "editor"]
        app.launch()
        let witness = app.descendants(matching: .any)["sharedComposeEditorShellWitness"].firstMatch
        XCTAssertTrue(scrollUntilHittable(witness, in: app, timeout: 12),
                      "sharedComposeEditorShellWitness was not reachable in the iOS bring-up surface.")
        attach(app, "33-shared-compose-editor-witness")
    }

    /// S4d-378: production shared `TextContentOption` — open sheet, focus/type, confirm, observe
    /// host label + watermarked preview still present after workflow re-render.
    func testSharedComposeTextContentChanges() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        attach(app, "30-launched-text-content")

        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30),
                      "Shared CMP watermark preview never appeared — fixture render required for re-render proof.")

        let marker = "S4d378-" + String(UUID().uuidString.prefix(8))
        applyWatermarkTextViaSharedCompose(marker, in: app)
        attach(app, "31-after-text-confirm")

        // Unique marker evidence only — do not assert newline-free exact labels.
        // OutlinedTextField is multi-line product behavior; Return (if used to expose Confirm)
        // may leave '\n' in the confirmed draft.
        let host = app.descendants(matching: .any)["sharedComposeTextContent"].firstMatch
        XCTAssertTrue(
            wait(forLabel: host, toContain: marker, timeout: 15),
            "Host accessibility label did not contain confirmed marker \(marker); label=\(host.label)"
        )
        XCTAssertTrue(preview.exists,
                      "Watermarked preview disappeared after shared text confirm (workflow re-render failed).")
        attach(app, "32-text-content-confirmed")
    }

    /// S4d-234 / S4d-378: Templates Save / Apply / Delete still work; text edits go through shared
    /// `TextContentOption` instead of the retired SwiftUI TextField + Apply path.
    func testTemplatesSaveApplyDelete() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        attach(app, "20-launched-templates")

        // Unique marker unlikely to collide with any seeded default template.
        let marker = "S4d234-" + String(UUID().uuidString.prefix(8))

        // 1. Persist marker via shared TextContentOption so workflow.watermarkText == marker.
        applyWatermarkTextViaSharedCompose(marker, in: app)

        // 2. Save current → a new template row labeled with the marker must appear.
        // Production ContentView sets accessibilityIdentifier("saveTemplateButton") on the real Button.
        // Query by semantic id only — never match templatesSection / ambiguous "Save current" labels.
        let saveBtn = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@", "saveTemplateButton"))
            .firstMatch
        XCTAssertTrue(
            scrollUntilHittable(saveBtn, in: app, timeout: 15),
            "Production saveTemplateButton missing (ContentView must set accessibilityIdentifier)."
        )
        XCTAssertEqual(saveBtn.label, "Save current",
                       "saveTemplateButton has unexpected label=\(saveBtn.label) identifier=\(saveBtn.identifier)")
        let saveEnabled = expectation(for: NSPredicate(format: "isEnabled == YES"), evaluatedWith: saveBtn, handler: nil)
        wait(for: [saveEnabled], timeout: 5)
        saveBtn.tap()
        attach(app, "21-after-save-current")

        // CONTAINS marker to find the new row; then use its per-id accessibilityIdentifier for delete.
        // Multi-line draft / optional Return may append '\n' — unique marker is enough for discovery.
        let rowByLabel = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
        XCTAssertTrue(scrollUntilHittable(rowByLabel, in: app, timeout: 15),
                      "Saved template row containing marker \(marker) never appeared.")
        let rowId = rowByLabel.identifier
        XCTAssertTrue(
            rowId.hasPrefix("templateRow-"),
            "Saved row identifier expected templateRow-<id>, got '\(rowId)' label=\(rowByLabel.label)"
        )
        let templateId = String(rowId.dropFirst("templateRow-".count))
        XCTAssertFalse(templateId.isEmpty, "Empty template id parsed from row identifier \(rowId)")
        let deleteId = "deleteTemplateButton-\(templateId)"
        let rowById = app.buttons.matching(NSPredicate(format: "identifier == %@", rowId)).firstMatch

        // 3. Apply: change text to a different baseline, then tap the saved row → host contains marker.
        applyWatermarkTextViaSharedCompose("otherValue", in: app)
        let host = app.descendants(matching: .any)["sharedComposeTextContent"].firstMatch
        XCTAssertTrue(
            wait(forLabel: host, toContain: "otherValue", timeout: 10),
            "Host label did not contain 'otherValue' after edit; label=\(host.label)"
        )

        XCTAssertTrue(scrollUntilHittable(rowById, in: app, timeout: 10),
                      "Saved template row id=\(rowId) was not reachable for Apply.")
        rowById.tap()
        XCTAssertTrue(
            wait(forLabel: host, toContain: marker, timeout: 15),
            "Tapping the saved template row did not put marker \(marker) into host label; label=\(host.label)"
        )
        attach(app, "22-after-apply-template")

        // 4. Delete via exact matching deleteTemplateButton-<id> (no Y-frame heuristic).
        let deleteBtn = app.buttons.matching(NSPredicate(format: "identifier == %@", deleteId)).firstMatch
        XCTAssertTrue(
            scrollUntilHittable(deleteBtn, in: app, timeout: 10),
            "Delete button \(deleteId) not found for saved template (marker \(marker), rowId \(rowId))."
        )
        deleteBtn.tap()
        attach(app, "23-after-delete")

        let goneRow = app.buttons.matching(NSPredicate(format: "identifier == %@", rowId)).firstMatch
        let removed = expectation(for: NSPredicate(format: "exists == NO"), evaluatedWith: goneRow, handler: nil)
        wait(for: [removed], timeout: 10)
        XCTAssertFalse(goneRow.exists,
                      "Saved template row \(rowId) was not removed by Delete (still present after tap).")
    }

    // MARK: - S4d-378 shared text helpers

    /// Open production `TextContentOption` sheet, replace text, confirm. Crashes from the old
    /// S4d-338 families surface as missing field/confirm or app death (test failure, not workaround).
    private func applyWatermarkTextViaSharedCompose(_ text: String, in app: XCUIApplication) {
        let host = app.descendants(matching: .any)["sharedComposeTextContent"].firstMatch
        XCTAssertTrue(scrollUntilHittable(host, in: app, timeout: 20),
                      "Production shared text-content control did not appear.")
        host.tap()

        // Prefer tagged Compose field; fall back to any text field/view after the sheet opens.
        let taggedField = app.descendants(matching: .any)["watermarkTextEditField"].firstMatch
        let anyField = app.textFields.firstMatch
        let anyTextView = app.textViews.firstMatch
        let field: XCUIElement
        if taggedField.waitForExistence(timeout: 8) {
            field = taggedField
        } else if anyField.waitForExistence(timeout: 3) {
            field = anyField
        } else if anyTextView.waitForExistence(timeout: 3) {
            field = anyTextView
        } else {
            XCTFail(
                "Shared text edit field never appeared after tapping TextContentOption " +
                "(S4d-338 ModalBottomSheet / IME path may still be broken on this CMP stack)."
            )
            return
        }

        clearAndType(in: field, text: text)

        // Prefer Confirm while IME remains (or an inside-sheet non-dismissal target).
        // Do NOT tap outside the sheet — that dismisses ModalBottomSheet without confirm.
        // Multi-line OutlinedTextField is product behavior; if Return is required to expose
        // Confirm, accept possible '\n' in the draft and assert only CONTAINS(marker) upstream.
        var confirm = resolveTextConfirmButton(in: app)
        if !confirm.waitForExistence(timeout: 3) || !confirm.isHittable {
            // Last resort: Return may scroll/reveal Confirm; may insert newline into multi-line draft.
            let returnKey = app.keyboards.buttons["Return"].firstMatch
            if returnKey.exists && returnKey.isHittable {
                returnKey.tap()
            }
            confirm = resolveTextConfirmButton(in: app)
        }
        XCTAssertTrue(confirm.waitForExistence(timeout: 8),
                      "watermarkTextConfirm testTag missing in TextContentOption sheet.")
        if confirm.isHittable {
            confirm.tap()
        } else {
            // Inside-sheet coordinate tap — still on the Confirm control, not outside dismissal.
            confirm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }

        // Sheet should dismiss after confirm; host label contains the typed text (not exact).
        // ContentView: accessibilityLabel("Watermark text \(workflow.watermarkText)").
        XCTAssertTrue(scrollUntilHittable(host, in: app, timeout: 15),
                      "Shared text host not reachable after confirm.")
        XCTAssertTrue(
            wait(forLabel: host, toContain: text, timeout: 15),
            "Host label did not contain confirmed text \(text); label=\(host.label)"
        )
    }

    /// Locate shared TextContentOption Confirm via the stable `watermarkTextConfirm` testTag only.
    /// Fail closed if the tag is missing — do not fall back to visible "Apply text" labels.
    private func resolveTextConfirmButton(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["watermarkTextConfirm"].firstMatch
    }

    /// Clear `field` and type `text`. Works for SwiftUI TextField and Compose-backed text inputs.
    /// Does not force single-line product behavior; Return is only used by the Confirm path if needed.
    private func clearAndType(in field: XCUIElement, text: String) {
        field.tap()
        if let current = field.value as? String, !current.isEmpty {
            // Select-all via long-press menu is flaky; delete char-by-char from end.
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
            // current.count + 4 covers the value plus a small optional trailing-newline cushion.
            let deleteCount = current.count + 4
            for _ in 0..<deleteCount {
                field.typeText(XCUIKeyboardKey.delete.rawValue)
            }
        }
        field.typeText(text)
    }

    @discardableResult
    private func wait(forLabel element: XCUIElement, toContain expected: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label.contains(expected) { return true }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return element.label.contains(expected)
    }

    /// Tap a nested CMP action via label-derived host X and the currently-visible host Y.
    private func tapSharedOutputAction(labeled labelElement: XCUIElement, in host: XCUIElement) {
        let hostFrame = host.frame
        let labelFrame = labelElement.frame
        XCTAssertGreaterThan(hostFrame.width, 1, "Saved-output host has zero width.")
        let normalizedX = (labelFrame.midX - hostFrame.minX) / hostFrame.width
        let clampedX = min(max(normalizedX, 0.05), 0.95)
        host.coordinate(withNormalizedOffset: CGVector(dx: clampedX, dy: 0.5)).tap()
    }

    @discardableResult
    private func scrollUntilHittable(_ element: XCUIElement, in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        let scrollView = app.scrollViews.firstMatch
        while Date() < deadline {
            if element.exists && element.isHittable { return true }
            if scrollView.exists {
                if element.exists && element.frame.minY < app.frame.minY {
                    scrollView.swipeDown()
                } else {
                    scrollView.swipeUp()
                }
            } else {
                app.swipeUp()
            }
        }
        return element.exists && element.isHittable
    }

    /// Poll the wrapper accessibility label, which changes only after the Swift workflow persists a slider value.
    @discardableResult
    private func waitForLabelChange(_ element: XCUIElement, from previousLabel: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label != previousLabel { return true }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return element.label != previousLabel
    }

}
