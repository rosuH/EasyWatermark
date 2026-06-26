import Foundation
import Photos

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
    }
}
