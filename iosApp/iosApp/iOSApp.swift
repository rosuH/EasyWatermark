import Foundation
import SwiftUI

/// Swift-side clock for pre-Kotlin cold start. Same line format as Kotlin `StartupTrace`.
enum EwmStartupTrace {
    static let t0 = CFAbsoluteTimeGetCurrent()
    static var enabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-ewmStartupTrace")
    }
    static func mark(_ name: String) {
        guard enabled else { return }
        let ms = Int(((CFAbsoluteTimeGetCurrent() - t0) * 1000.0).rounded())
        NSLog("EWM_STARTUP mark=%@ t_ms=%d clock=swift", name as NSString, ms)
    }
}

// C5.1 (S4d-25): minimal SwiftUI app entry point. This is the first iOS application target for
// EasyWatermark; its sole purpose at this slice is to prove that an iOS app can link the `:shared`
// KMP framework and build for the simulator SDK. No watermark UI, no fonts, no image picker yet —
// those land in later C5 slices (C5.2 fonts, C5.4 PHPicker flow).
@main
struct EasyWatermarkApp: App {
    init() {
        EwmStartupTrace.mark("swift_app_init")
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Product is forced-dark (parity with Android production); light status bar chrome.
                .preferredColorScheme(.dark)
        }
    }
}
