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

    /// Fresh app process with fixture image; terminates any prior instance first for isolation.
    private func launchFixtureApp() -> XCUIApplication {
        let app = XCUIApplication()
        if app.state == .runningForeground || app.state == .runningBackground {
            app.terminate()
        }
        app.launchArguments = ["-uiTestFixtureImage", "1"]
        app.launch()
        return app
    }

    /// Real ColorSwatch node (`contentDescription = "Text color #AARRGGBB"`), preferring descendants
    /// of the palette host so we do not match an unrelated node.
    private func colorSwatch(labeled label: String, under host: XCUIElement, in app: XCUIApplication) -> XCUIElement {
        let underHost = host.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
        if underHost.exists { return underHost }
        return app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
    }

    /// The real proof: fixture image → shared render → SaveExportSheetShell → Photos/Share edges.
    /// C2: export **panel** is shared Android Compose shell; write/share remain platform (E09/E10).
    func testFixtureRenderPreviewAndExport() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        attach(app, "01-launched-with-fixture")

        // Render runs on launch via the fixture seam; wait for the real shared CMP preview host.
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30),
                      "Shared CMP watermark preview never appeared — fixture render did not reach the host.")
        attach(app, "02-watermarked-preview")

        // Open shared SaveExportSheetShell (same panel as Android Compose export sheet).
        let saveButton = app.descendants(matching: .any)["sharedComposeSaveButton"].firstMatch
        XCTAssertTrue(saveButton.waitForExistence(timeout: 15),
                      "Editor Save top-bar button (sharedComposeSaveButton) never appeared.")
        saveButton.tap()

        let exportPrimary = app.descendants(matching: .any)["sharedComposeExportPrimary"].firstMatch
        XCTAssertTrue(exportPrimary.waitForExistence(timeout: 10),
                      "SaveExportSheetShell primary CTA (sharedComposeExportPrimary) never appeared.")
        attach(app, "02b-export-sheet")

        // First primary = Export → platform Save-to-Photos edge (async).
        exportPrimary.tap()

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow Access to All Photos", "Allow Full Access", "Allow", "OK", "允许", "好"] {
            let b = springboard.buttons[label].firstMatch
            if b.waitForExistence(timeout: 4) { b.tap(); break }
        }
        // Primary flips to Share (en) / 分享 (zh) after export orchestration marks finished.
        let shareReady = expectation(
            for: NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Share", "分享"),
            evaluatedWith: exportPrimary,
            handler: nil,
        )
        wait(for: [shareReady], timeout: 20)
        attach(app, "03-after-save")
        let saveFailed = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'Save failed'")).firstMatch
        XCTAssertFalse(saveFailed.exists, "Save-to-Photos reported 'Save failed': \(saveFailed.label)")

        // Second primary = Share → system share sheet (E09 mechanism).
        XCTAssertTrue(exportPrimary.waitForExistence(timeout: 5), "Export primary CTA gone before Share.")
        exportPrimary.tap()
        let shareSheet = app.otherElements["ActivityListView"].firstMatch
        let copyAction = app.buttons["Copy"].firstMatch
        let shareSheetAppeared = shareSheet.waitForExistence(timeout: 10) || copyAction.waitForExistence(timeout: 5)
        attach(app, "04-share-sheet")
        XCTAssertTrue(shareSheetAppeared,
                      "System share sheet did not appear after tapping Share (no ActivityListView / Copy action).")
    }

    /// About from Launch: back must return to Launch (not Editor). Version must not be literal "iOS".
    func testAboutFromLaunchBacksToLaunch() {
        let app = XCUIApplication()
        if app.state == .runningForeground || app.state == .runningBackground {
            app.terminate()
        }
        app.launchArguments = []
        app.launch()

        let launch = app.descendants(matching: .any)["sharedComposeLaunchScreen"].firstMatch
        XCTAssertTrue(launch.waitForExistence(timeout: 20), "Launch screen never appeared.")
        let aboutBtn = app.descendants(matching: .any)["launchAboutButton"].firstMatch
        XCTAssertTrue(aboutBtn.waitForExistence(timeout: 10), "Launch About control missing.")
        aboutBtn.tap()

        // Version row trailing should show product version 2.10.0 (not "iOS").
        let versionLabel = app.staticTexts.matching(NSPredicate(format: "label == %@", "2.10.0")).firstMatch
        XCTAssertTrue(versionLabel.waitForExistence(timeout: 15),
                      "About version 2.10.0 not visible (got wrong/missing version).")
        attach(app, "about-from-launch")

        let back = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR identifier CONTAINS[c] %@", "Back", "back")).firstMatch
        if back.waitForExistence(timeout: 5) {
            back.tap()
        } else {
            // Fallback: top-leading hit.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.08)).tap()
        }
        XCTAssertTrue(launch.waitForExistence(timeout: 10),
                      "Back from About did not restore Launch (likely wrong Editor route).")
        XCTAssertFalse(app.descendants(matching: .any)["sharedComposeEditorScreen"].firstMatch.exists,
                       "About back incorrectly showed Editor.")
        attach(app, "about-back-to-launch")
    }

    /// Regression: bottom Content/Style/Layout tabs must switch without SIGABRT (custom tab indicator
    /// measure path previously crashed on iOS when switching back to the first tab).
    func testEditorBottomTabsDoNotCrash() {
        let app = launchFixtureApp()
        let preview = app.descendants(matching: .any)["sharedComposeWatermarkPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30), "Editor never appeared with fixture.")

        func tapTab(_ labels: [String]) {
            for label in labels {
                let el = app.descendants(matching: .any)
                    .matching(NSPredicate(format: "label == %@", label))
                    .firstMatch
                if el.waitForExistence(timeout: 3), el.frame.height > 8 {
                    el.tap()
                    return
                }
            }
            XCTFail("Could not find tab among labels: \(labels)")
        }

        // Cycle Style → Layout → Content (first tab) → Style again.
        tapTab(["Style", "样式"])
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 2))
        tapTab(["Layout", "布局"])
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 2))
        tapTab(["Content", "内容"])
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 2))
        tapTab(["Style", "样式"])
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 2))
        tapTab(["Content", "内容"])
        XCTAssertEqual(app.state, .runningForeground, "App crashed after tab switches.")
        XCTAssertTrue(preview.exists, "Preview host gone after tab switches — process likely crashed.")
        attach(app, "tab-switch-ok")
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
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 25),
                      "Production shared text-style control did not appear.")

        // Real segment nodes (segment contentDescription / label), not normalized host coordinates.
        // Stroke first so we always leave the default Fill.
        let strokeSeg = segmentChoice(named: "Stroke", in: app)
        XCTAssertTrue(scrollUntilHittable(strokeSeg, in: app, timeout: 10), "Stroke segment was not reachable.")
        tapIfPossible(strokeSeg)
        let strokeSelected = expectation(for: NSPredicate(format: "label == %@", "Text style Stroke"),
                                         evaluatedWith: control, handler: nil)
        wait(for: [strokeSelected], timeout: 15)

        let fillSeg = segmentChoice(named: "Fill", in: app)
        XCTAssertTrue(scrollUntilHittable(fillSeg, in: app, timeout: 10), "Fill segment was not reachable.")
        tapIfPossible(fillSeg)
        let fillSelected = expectation(for: NSPredicate(format: "label == %@", "Text style Fill"),
                                       evaluatedWith: control, handler: nil)
        wait(for: [fillSelected], timeout: 15)

        // End on Stroke for the relaunch persistence proof.
        XCTAssertTrue(scrollUntilHittable(strokeSeg, in: app, timeout: 10), "Stroke segment not reachable for final select.")
        tapIfPossible(strokeSeg)
        let strokeAgain = expectation(for: NSPredicate(format: "label == %@", "Text style Stroke"),
                                      evaluatedWith: control, handler: nil)
        wait(for: [strokeAgain], timeout: 15)

        app.terminate()
        app.launch()
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-style control did not reappear after reload.")
        let persistedStroke = expectation(for: NSPredicate(format: "label == %@", "Text style Stroke"),
                                          evaluatedWith: control, handler: nil)
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
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 25),
                      "Production shared typeface control did not appear.")

        // Real segment nodes; Bold first so we always leave default Normal.
        let boldSeg = segmentChoice(named: "Bold", in: app)
        XCTAssertTrue(scrollUntilHittable(boldSeg, in: app, timeout: 10), "Bold segment was not reachable.")
        tapIfPossible(boldSeg)
        let boldSelected = expectation(for: NSPredicate(format: "label == %@", "Typeface Bold"),
                                       evaluatedWith: control, handler: nil)
        wait(for: [boldSelected], timeout: 15)

        let normalSeg = segmentChoice(named: "Normal", in: app)
        XCTAssertTrue(scrollUntilHittable(normalSeg, in: app, timeout: 10), "Normal segment was not reachable.")
        tapIfPossible(normalSeg)
        let normalSelected = expectation(for: NSPredicate(format: "label == %@", "Typeface Normal"),
                                         evaluatedWith: control, handler: nil)
        wait(for: [normalSelected], timeout: 15)

        XCTAssertTrue(scrollUntilHittable(boldSeg, in: app, timeout: 10), "Bold segment not reachable for final select.")
        tapIfPossible(boldSeg)
        let boldAgain = expectation(for: NSPredicate(format: "label == %@", "Typeface Bold"),
                                    evaluatedWith: control, handler: nil)
        wait(for: [boldAgain], timeout: 15)

        app.terminate()
        app.launch()
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared typeface control did not reappear after reload.")
        let persistedBold = expectation(for: NSPredicate(format: "label == %@", "Typeface Bold"),
                                        evaluatedWith: control, handler: nil)
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
        // Deterministic isolation: prior suite runs showed rare XCTest "Lost connection to the
        // application" (mach_error=10000003) without product crash logs. Fresh terminate+launch
        // avoids inter-test process contamination; behavior under test is unchanged.
        let app = launchFixtureApp()

        let control = app.descendants(matching: .any)["sharedComposeWatermarkHGap"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 25),
                      "Production shared horizontal-gap control did not appear.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.35))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.35))
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
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
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
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 25),
                      "Production shared vertical-gap control did not appear.")
        // Extra options nudge: V-gap sits near the end of the column and needs a full track hit.
        let options = app.descendants(matching: .any)["sharedComposeEditorOptions"].firstMatch
        if options.exists {
            options.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
                .press(forDuration: 0.05, thenDragTo: options.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)))
        }
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 10),
                      "Vertical-gap control not fully on-screen after options nudge.")

        let initialLabel = control.label
        let leftTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.35))
        let rightTrack = control.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.35))
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
        let app = launchFixtureApp()

        let control = app.descendants(matching: .any)["sharedComposeTextColor"].firstMatch
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-color control did not appear.")

        // Production host + ColorSwatch share "Text color #AARRGGBB" (formatArgbHexColor).
        // iOS palette order: amber #FFFFB800, white, black, red. Prefer red — never the default amber.
        let redLabel = "Text color #FFFF0000"
        let whiteLabel = "Text color #FFFFFFFF"
        let redSwatch = colorSwatch(labeled: redLabel, under: control, in: app)
        XCTAssertTrue(scrollUntilHittable(redSwatch, in: app, timeout: 15),
                      "Red palette swatch (\(redLabel)) was not reachable.")
        tapIfPossible(redSwatch)

        let redSelected = expectation(for: NSPredicate(format: "label == %@", redLabel),
                                      evaluatedWith: control, handler: nil)
        if XCTWaiter.wait(for: [redSelected], timeout: 8) != .completed {
            // If red was already selected (persisted), prove a different real swatch changes state.
            let whiteSwatch = colorSwatch(labeled: whiteLabel, under: control, in: app)
            XCTAssertTrue(scrollUntilHittable(whiteSwatch, in: app, timeout: 10),
                          "White palette swatch (\(whiteLabel)) was not reachable.")
            tapIfPossible(whiteSwatch)
            let whiteSelected = expectation(for: NSPredicate(format: "label == %@", whiteLabel),
                                            evaluatedWith: control, handler: nil)
            wait(for: [whiteSelected], timeout: 15)
        }

        let selectedLabel = control.label
        XCTAssertTrue(
            selectedLabel == redLabel || selectedLabel == whiteLabel,
            "Host label must equal a real palette hex string after swatch tap; label=\(selectedLabel)"
        )

        app.terminate()
        app.launch()
        XCTAssertTrue(scrollUntilHittable(control, in: app, timeout: 20),
                      "Production shared text-color control did not reappear after reload.")
        let persistedColor = expectation(for: NSPredicate(format: "label == %@", selectedLabel),
                                         evaluatedWith: control, handler: nil)
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

        // Exact single-line marker (no embedded newlines). Product multi-line remains allowed;
        // the helper clears the field fully so we can assert an exact production label.
        let marker = "S4d378-" + String(UUID().uuidString.prefix(8))
        applyWatermarkTextViaSharedCompose(marker, in: app)
        attach(app, "31-after-text-confirm")

        let host = app.descendants(matching: .any)["sharedComposeTextContent"].firstMatch
        let expectedLabel = "Watermark text \(marker)"
        XCTAssertTrue(
            wait(forLabel: host, toEqual: expectedLabel, timeout: 15),
            "Host accessibility label must equal exact production string \(expectedLabel); label=\(host.label)"
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
            wait(forLabel: host, toEqual: "Watermark text otherValue", timeout: 10),
            "Host label must equal exact 'Watermark text otherValue' after edit; label=\(host.label)"
        )

        XCTAssertTrue(scrollUntilHittable(rowById, in: app, timeout: 10),
                      "Saved template row id=\(rowId) was not reachable for Apply.")
        rowById.tap()
        XCTAssertTrue(
            wait(forLabel: host, toEqual: "Watermark text \(marker)", timeout: 15),
            "Tapping the saved template row must restore exact host label Watermark text \(marker); label=\(host.label)"
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

    /// Open production `TextContentOption` sheet, replace text exactly, confirm.
    /// Does not trim product multi-line capability: the field may be multi-line, but this helper
    /// fully clears prior content and types `text` without inserting Return/newlines.
    private func applyWatermarkTextViaSharedCompose(_ text: String, in app: XCUIApplication) {
        // Callers pass exact markers without embedded newlines so the production host label can
        // be asserted with full equality: "Watermark text \(text)".
        XCTAssertFalse(text.contains("\n"), "Test helper must not inject multi-line watermark text.")
        let host = app.descendants(matching: .any)["sharedComposeTextContent"].firstMatch
        XCTAssertTrue(scrollUntilHittable(host, in: app, timeout: 20),
                      "Production shared text-content control did not appear.")
        host.tap()

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

        clearAndTypeExact(in: field, app: app, text: text)

        // Prefer Confirm while IME remains. Do NOT tap Return (inserts newline into multi-line field).
        // If Confirm is off-screen, swipe the sheet scroll region, not the keyboard Return key.
        var confirm = resolveTextConfirmButton(in: app)
        if !confirm.waitForExistence(timeout: 3) || !confirm.isHittable {
            let sheetScroll = app.scrollViews.firstMatch
            if sheetScroll.exists {
                sheetScroll.swipeUp()
            } else {
                app.swipeUp()
            }
            confirm = resolveTextConfirmButton(in: app)
        }
        XCTAssertTrue(confirm.waitForExistence(timeout: 8),
                      "watermarkTextConfirm testTag missing in TextContentOption sheet.")
        if confirm.isHittable {
            confirm.tap()
        } else {
            confirm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }

        // Production host semantics: contentDescription = "Watermark text \(workflow.watermarkText)".
        let expected = "Watermark text \(text)"
        XCTAssertTrue(scrollUntilHittable(host, in: app, timeout: 15),
                      "Shared text host not reachable after confirm.")
        XCTAssertTrue(
            wait(forLabel: host, toEqual: expected, timeout: 15),
            "Host label must equal exact production string \(expected); label=\(host.label)"
        )
    }

    /// Locate shared TextContentOption Confirm via the stable `watermarkTextConfirm` testTag only.
    /// Fail closed if the tag is missing — do not fall back to visible "Apply text" labels.
    private func resolveTextConfirmButton(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["watermarkTextConfirm"].firstMatch
    }

    /// Named segment button/label under the production CMP host (Fill/Stroke/Normal/Bold/…).
    /// Prefers exact accessibility label, then content-description-style matches.
    private func segmentChoice(named name: String, in app: XCUIApplication) -> XCUIElement {
        let exact = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", name))
            .firstMatch
        if exact.exists { return exact }
        return app.staticTexts.matching(NSPredicate(format: "label == %@", name)).firstMatch
    }

    /// Tap an a11y node; if XCUITest reports not-hittable at the edge of the options viewport,
    /// use the element's own mid-point coordinate (still the real swatch/segment node).
    private func tapIfPossible(_ element: XCUIElement) {
        if element.isHittable {
            element.tap()
        } else {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
    }

    /// Clear `field` completely and type `text` with no inserted Return/newlines.
    /// Uses Select All when the edit menu is available; otherwise deletes the full `value` string.
    private func clearAndTypeExact(in field: XCUIElement, app: XCUIApplication, text: String) {
        field.tap()
        // Prefer Select All so multi-line drafts (including trailing newlines) are replaced exactly.
        field.press(forDuration: 0.9)
        let selectAll = app.menuItems["Select All"].firstMatch
        if selectAll.waitForExistence(timeout: 2), selectAll.isHittable {
            selectAll.tap()
            field.typeText(text)
            return
        }
        // Menu unavailable: delete every character of the current value (no +N cushion → no leftover \n).
        if let current = field.value as? String, !current.isEmpty {
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
            for _ in 0..<current.count {
                field.typeText(XCUIKeyboardKey.delete.rawValue)
            }
            // Second pass if placeholder/value still non-empty after first wipe.
            if let still = field.value as? String, !still.isEmpty, still != text {
                for _ in 0..<still.count {
                    field.typeText(XCUIKeyboardKey.delete.rawValue)
                }
            }
        }
        field.typeText(text)
    }

    @discardableResult
    private func wait(forLabel element: XCUIElement, toEqual expected: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label == expected { return true }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return element.label == expected
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

    /// Resolve Share / Save-to-Photos under the tagged host, app-wide, or via host X fallback.
    private func tapSavedOutputAction(
        named name: String,
        host: XCUIElement,
        in app: XCUIApplication,
        normalizedX: CGFloat
    ) {
        let underHost = host.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", name)).firstMatch
        if underHost.waitForExistence(timeout: 2) {
            tapSharedOutputAction(labeled: underHost, in: host)
            return
        }
        let appWide = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", name)).firstMatch
        if appWide.waitForExistence(timeout: 3) {
            tapSharedOutputAction(labeled: appWide, in: host)
            return
        }
        // Last resort: primary (Share) is left, secondary (Save to Photos) is right in SavedOutputActions.
        XCTAssertGreaterThan(host.frame.width, 1, "\(name): saved-output host has zero width.")
        host.coordinate(withNormalizedOffset: CGVector(dx: normalizedX, dy: 0.5)).tap()
    }

    /// Scroll until [element] is on-screen enough to coordinate-tap.
    ///
    /// S4d-383 single-host CMP:
    /// - `sharedCompose*` controls scroll via Compose `sharedComposeEditorOptions` (never the
    ///   Templates-only SwiftUI ScrollView — swiping that strip cannot reveal CMP tags).
    /// - Templates / witnesses use the templates strip ScrollView or app-level swipes.
    /// - Nested Compose nodes often stay `isHittable == false`; a non-empty on-screen frame is enough.
    @discardableResult
    private func scrollUntilHittable(_ element: XCUIElement, in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        let options = app.descendants(matching: .any)["sharedComposeEditorOptions"].firstMatch
        let templatesScroll = app.descendants(matching: .any)["templatesSection"].firstMatch
        while Date() < deadline {
            if elementIsOnScreen(element, in: app) {
                return true
            }
            let dir: ScrollDir = {
                if element.exists && element.frame.maxY < app.frame.minY + 8 { return .down }
                return .up
            }()
            let id = element.identifier
            if id.hasPrefix("sharedCompose") {
                swipeComposeOptions(options: options, app: app, direction: dir)
            } else if id.hasPrefix("saveTemplate") || id.hasPrefix("templateRow")
                        || id.hasPrefix("deleteTemplate") || id == "templatesSection" {
                swipeTemplates(templatesScroll: templatesScroll, app: app, direction: dir)
            } else if id.contains("Witness") {
                // Witnesses sit below the fill-height host; app swipe moves the root VStack if needed.
                if dir == .up { app.swipeUp() } else { app.swipeDown() }
            } else {
                // Default: try options, then app.
                if options.exists, options.frame.height > 20 {
                    swipeComposeOptions(options: options, app: app, direction: dir)
                } else {
                    if dir == .up { app.swipeUp() } else { app.swipeDown() }
                }
            }
        }
        return elementIsOnScreen(element, in: app)
    }

    private enum ScrollDir { case up, down }

    private func elementIsOnScreen(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        // Reject empty/clipped stubs; allow short StaticText segment labels (~14–22pt).
        guard frame.width > 6, frame.height > 6 else { return false }
        let visible = app.frame.insetBy(dx: 0, dy: 4)
        // For CMP editor options only: keep midpoints above the Templates strip so swatches/segments
        // are not considered reachable when covered by the strip. Do NOT apply this to Templates /
        // witnesses themselves (they live in or below that strip by design).
        var usable = visible
        let id = element.identifier
        let isEditorOption =
            id.hasPrefix("sharedCompose")
            || id.hasPrefix("Text color #")
            || id == "Fill" || id == "Stroke"
            || id == "Normal" || id == "Bold" || id == "Italic" || id == "BoldItalic"
            || id == "Repeat" || id == "Single"
        if isEditorOption {
            let templates = app.descendants(matching: .any)["templatesSection"].firstMatch
            if templates.exists, templates.frame.height > 10 {
                let topOfTemplates = templates.frame.minY
                if topOfTemplates > usable.minY + 40 {
                    usable = CGRect(
                        x: usable.minX,
                        y: usable.minY,
                        width: usable.width,
                        height: max(40, topOfTemplates - usable.minY - 4),
                    )
                }
            }
        }
        let intersection = frame.intersection(usable)
        guard !intersection.isNull, intersection.width > 4, intersection.height > 4 else { return false }
        if frame.height >= 36, frame.width >= 80 {
            // Large host/slider wrappers: nearly full height so track taps (dy≈0.25) hit the slider.
            return intersection.height >= frame.height * 0.9 && intersection.width >= frame.width * 0.55
        }
        if isEditorOption {
            return usable.contains(CGPoint(x: frame.midX, y: frame.midY))
        }
        return true
    }

    private func swipeTemplates(templatesScroll: XCUIElement, app: XCUIApplication, direction: ScrollDir) {
        if templatesScroll.exists, templatesScroll.frame.height > 20 {
            if direction == .up { templatesScroll.swipeUp() } else { templatesScroll.swipeDown() }
            return
        }
        if direction == .up { app.swipeUp() } else { app.swipeDown() }
    }

    private func swipeComposeOptions(
        options: XCUIElement,
        app: XCUIApplication,
        direction: ScrollDir
    ) {
        if options.exists, options.frame.height > 20 {
            let startY: CGFloat = direction == .up ? 0.88 : 0.15
            let endY: CGFloat = direction == .up ? 0.18 : 0.88
            let start = options.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: startY))
            let end = options.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: endY))
            start.press(forDuration: 0.05, thenDragTo: end)
            return
        }
        // Options not exposed yet — nudge the lower half of the screen (options live under preview).
        let startY: CGFloat = direction == .up ? 0.78 : 0.45
        let endY: CGFloat = direction == .up ? 0.40 : 0.78
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: startY))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: endY))
        start.press(forDuration: 0.05, thenDragTo: end)
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
