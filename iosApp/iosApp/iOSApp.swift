import SwiftUI

// C5.1 (S4d-25): minimal SwiftUI app entry point. This is the first iOS application target for
// EasyWatermark; its sole purpose at this slice is to prove that an iOS app can link the `:shared`
// KMP framework and build for the simulator SDK. No watermark UI, no fonts, no image picker yet —
// those land in later C5 slices (C5.2 fonts, C5.4 PHPicker flow).
@main
struct EasyWatermarkApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Product is forced-dark (parity with Android production); light status bar chrome.
                .preferredColorScheme(.dark)
        }
    }
}
