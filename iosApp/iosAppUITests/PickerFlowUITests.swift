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
        continueAfterFailure = true
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

        // Render runs on launch via the fixture seam; wait for the real preview + status.
        let preview = app.images["Watermarked preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30),
                      "Watermarked preview never appeared — fixture render did not reach the preview.")
        let status = app.staticTexts["renderStatus"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 5),
                      "renderStatus ('Watermarked …') text not found.")
        XCTAssertTrue(status.label.localizedCaseInsensitiveContains("Watermarked"),
                      "renderStatus did not report a watermarked result: \(status.label)")
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
}
