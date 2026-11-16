import Foundation
import Photos
import StoreKit
import UIKit

// C5.4 (S4d-29): export helpers for the watermarked PNG produced by `WatermarkWorkflow`.
//
// Two affordances:
//   * `writeTemporaryPNG` stages the bytes as a real `.png` file so SwiftUI `ShareLink` can offer the
//     full system share sheet (Share / Save Image / Save to Files / AirDrop / …) with a proper
//     filename + UTI, instead of sharing a raw in-memory blob.
//   * `saveToPhotos` writes the PNG straight into the user's photo library (add-only), the direct
//     "save" path.
//
// S4d-58 runtime-proves both export affordances through XCUITest: Save reaches "Saved to Photos" and
// Share presents the system share sheet.

enum ImageExportError: LocalizedError {
    case photoLibraryAccessDenied

    var errorDescription: String? {
        switch self {
        case .photoLibraryAccessDenied:
            return "Photo library access was not granted."
        }
    }
}

enum ImageExport {

    /// Write PNG bytes to a temporary `.png` file and return its URL (for `ShareLink`).
    /// The file is overwritten atomically on each export so the share item is always the latest render.
    static func writeTemporaryPNG(_ data: Data, fileName: String = "EasyWatermark.png") throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Save PNG bytes to the photo library (add-only). Requests add-only authorization first and
    /// preserves the exact encoded bytes via `addResource(with: .photo, data:)` (no re-encode).
    /// Requires `NSPhotoLibraryAddUsageDescription` at runtime (added as an Info.plist build setting).
    static func saveToPhotos(_ data: Data) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw ImageExportError.photoLibraryAccessDenied
        }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, data: data, options: nil)
        }
        AppStoreReviewCoordinator.recordSuccessfulSaveAndRequestReviewIfEligible()
    }
}

/// Strictly offline in-app review prompt coordinator using UserDefaults counters.
/// Prompts only on the 2nd and 8th successful Photos save, and skips all UI test / capture modes.
enum AppStoreReviewCoordinator {
    private static let saveCountKey = "ewm.ios.successfulPhotosSaveCount"
    private static let lastPromptedCountKey = "ewm.ios.lastReviewPromptSaveCount"
    private static let promptMilestones: Set<Int> = [2, 8]
    private static let testLaunchFlags: Set<String> = [
        "-uiTestFixtureImage",
        "-storeSeedScene",
        "-storeCaptureHooks",
        "-sharedComposeWitnesses",
    ]

    static func recordSuccessfulSaveAndRequestReviewIfEligible(
        defaults: UserDefaults = .standard,
        arguments: [String] = ProcessInfo.processInfo.arguments
    ) {
        guard arguments.allSatisfy({ !testLaunchFlags.contains($0) }) else { return }
        let nextCount = defaults.integer(forKey: saveCountKey) + 1
        defaults.set(nextCount, forKey: saveCountKey)
        guard promptMilestones.contains(nextCount),
              defaults.integer(forKey: lastPromptedCountKey) < nextCount
        else { return }
        defaults.set(nextCount, forKey: lastPromptedCountKey)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
            else { return }
            AppStore.requestReview(in: scene)
        }
    }
}
