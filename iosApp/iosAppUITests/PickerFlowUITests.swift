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
        attach(app, "02-watermarked-preview")

        // Export UI — Save to Photos (handles the add-only permission alert) then Share.
        let saveButton = app.buttons["Save to Photos"].firstMatch
        XCTAssertTrue(saveButton.waitForExistence(timeout: 5), "Save to Photos button missing.")
        saveButton.tap()
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

        // Share — assert the ShareLink actually presents the system share sheet. The share sheet is
        // hosted by the system; try the classic `ActivityListView` and the sheet's known actions.
        let shareButton = app.buttons["Share"].firstMatch
        XCTAssertTrue(shareButton.waitForExistence(timeout: 5), "Share button missing.")
        shareButton.tap()
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

        let normal = app.staticTexts.matching(NSPredicate(format: "label == %@", "Normal")).firstMatch
        XCTAssertTrue(scrollUntilHittable(normal, in: app, timeout: 10), "Normal typeface choice was not reachable.")
        normal.tap()
        let normalSelected = expectation(for: NSPredicate(format: "label == %@", "Typeface Normal"), evaluatedWith: control, handler: nil)
        wait(for: [normalSelected], timeout: 15)

        let bold = app.staticTexts.matching(NSPredicate(format: "label == %@", "Bold")).firstMatch
        XCTAssertTrue(scrollUntilHittable(bold, in: app, timeout: 10), "Bold typeface choice was not reachable.")
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

    /// Documents the S4d-57-proven capability without asserting the blocked selection step:
    /// the out-of-process PHPicker OPENS from the app. Kept green (no selection assertion).
    func testPhotosPickerOpens() {
        let app = XCUIApplication()
        app.launch()
        let pickButton = app.buttons["pickPhotoButton"].firstMatch
        XCTAssertTrue(pickButton.waitForExistence(timeout: 20), "Pick a photo button not found")
        pickButton.tap()
        // The picker is exposed to XCUITest as a scrollView (S4d-57). Assert only that it opens.
        let pickerScroll = app.scrollViews.firstMatch
        XCTAssertTrue(pickerScroll.waitForExistence(timeout: 12),
                      "PhotosPicker did not open.")
        attach(app, "10-picker-opened")
        // NOTE: grid-cell SELECTION is NOT asserted — unaddressable on this beta toolchain (S4d-57);
        // the render/export path is proven via the fixture seam in testFixtureRenderPreviewAndExport.
    }

    /// S4d-323: normal debug launches must not show the CMP host witnesses. They are test-only
    /// runtime/link proof, enabled only by the `-sharedComposeWitnesses` launch argument.
    func testSharedComposeWitnessesHiddenByDefault() {
        let app = XCUIApplication()
        app.launch()
        let pickButton = app.buttons["pickPhotoButton"].firstMatch
        XCTAssertTrue(pickButton.waitForExistence(timeout: 20), "Pick a photo button not found")

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

    /// S4d-234: proves the S4d-233 Templates UI works end-to-end through the app:
    ///   1. Save current creates a visible template row for a unique marker string.
    ///   2. Apply (tapping the saved row) updates the watermark text field back to that marker.
    ///   3. Delete removes that row from the UI.
    /// Uses the existing `-uiTestFixtureImage` DEBUG seam so the render path has a deterministic image
    /// without addressing the blocked PHPicker grid cells. Not 1:1 Android v2.10.0 parity.
    func testTemplatesSaveApplyDelete() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestFixtureImage", "1"]
        app.launch()
        attach(app, "20-launched-templates")

        // Unique marker unlikely to collide with any seeded default template.
        let marker = "S4d234-" + String(UUID().uuidString.prefix(8))

        // 1. Replace the watermark text with the marker and Apply so the workflow's `watermarkText`
        //    equals the marker. `Save current` reads `watermarkText` (not the draft), so Apply first.
        let textField = app.textFields["watermarkTextField"].firstMatch
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "Watermark text field missing.")
        clearAndType(in: textField, text: marker)
        dismissKeyboard(in: app)
        let applyBtn = app.buttons["applyWatermarkText"].firstMatch
        XCTAssertTrue(applyBtn.waitForExistence(timeout: 5), "Apply button missing.")
        applyBtn.tap()

        // 2. Save current → a new template row labeled with the marker must appear.
        let saveBtn = app.buttons["Save current"].firstMatch
        XCTAssertTrue(scrollUntilHittable(saveBtn, in: app, timeout: 10), "Save current button missing.")
        let saveEnabled = expectation(for: NSPredicate(format: "isEnabled == YES"), evaluatedWith: saveBtn, handler: nil)
        wait(for: [saveEnabled], timeout: 5)
        saveBtn.tap()
        attach(app, "21-after-save-current")

        let rowPredicate = NSPredicate(format: "label == %@", marker)
        let savedRow = app.buttons[marker].firstMatch
        XCTAssertTrue(scrollUntilHittable(savedRow, in: app, timeout: 10),
                      "Saved template row with label \(marker) never appeared.")

        // 3. Apply: change the text to a different baseline, Apply, then tap the saved row.
        //    The text field must revert to the marker (applyTemplate → setWatermarkText → draftText sync).
        XCTAssertTrue(scrollUntilHittable(textField, in: app, timeout: 10), "Watermark text field not reachable.")
        clearAndType(in: textField, text: "otherValue")
        dismissKeyboard(in: app)
        applyBtn.tap()
        XCTAssertTrue(wait(forTextField: textField, toEqual: "otherValue", timeout: 10),
                      "Text field did not reflect 'otherValue' after Apply (pre-template baseline).")

        XCTAssertTrue(scrollUntilHittable(savedRow, in: app, timeout: 10),
                      "Saved template row with label \(marker) was not reachable for Apply.")
        savedRow.tap()
        XCTAssertTrue(wait(forTextField: textField, toEqual: marker, timeout: 10),
                      "Tapping the saved template row did not update the watermark text field to \(marker).")
        attach(app, "22-after-apply-template")

        // 4. Delete the saved row → it must disappear from the UI.
        guard let deleteBtn = deleteButtonOnSameRow(as: savedRow, in: app) else {
            XCTFail("Delete button not found on the same row as the saved template (label \(marker)).")
            return
        }
        deleteBtn.tap()
        attach(app, "23-after-delete")

        let goneRow = app.buttons.matching(rowPredicate).firstMatch
        let removed = expectation(for: NSPredicate(format: "exists == NO"), evaluatedWith: goneRow, handler: nil)
        wait(for: [removed], timeout: 10)
        XCTAssertFalse(goneRow.exists,
                      "Saved template row was not removed by Delete (still present after tap).")
    }

    // MARK: - S4d-234 helpers

    /// Clear `field` and type `text`. Taps near the right edge first so the cursor sits at the end of any
    /// existing text, then deletes backward before typing. Works for SwiftUI `TextField` with
    /// `.roundedBorder` (no reliance on the optional clear-button or edit menu).
    private func clearAndType(in field: XCUIElement, text: String) {
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        if let current = field.value as? String {
            for _ in 0..<current.count {
                field.typeText(XCUIKeyboardKey.delete.rawValue)
            }
        }
        field.typeText(text)
    }

    private func dismissKeyboard(in app: XCUIApplication) {
        if app.keyboards.count == 0 { return }
        let returnKey = app.keyboards.buttons["Return"].firstMatch
        if returnKey.exists {
            returnKey.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
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

    /// Poll until `field.value` equals `expected` or `timeout` elapses.
    @discardableResult
    private func wait(forTextField field: XCUIElement, toEqual expected: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if (field.value as? String) == expected { return true }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return (field.value as? String) == expected
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

    /// Find the `deleteTemplateButton` whose vertical center matches `row`'s — they are siblings in the
    /// same HStack, so they share the same mid-Y. Falls back to nil if none is on the same line.
    private func deleteButtonOnSameRow(as row: XCUIElement, in app: XCUIApplication) -> XCUIElement? {
        let rowY = row.frame.midY
        let candidates = app.buttons.matching(
            NSPredicate(format: "identifier == %@ OR label == %@", "deleteTemplateButton", "Delete template")
        ).allElementsBoundByIndex
        return candidates.first { abs($0.frame.midY - rowY) < 12 }
    }
}
