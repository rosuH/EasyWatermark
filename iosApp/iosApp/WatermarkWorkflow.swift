import Foundation
import Shared

// C5.4 (S4d-27/31/58): the iOS watermark workflow — picked-photo bytes to a watermarked PNG through
// the `:shared` iOS render bridge.
//
// Pipeline (all in `:shared`, decode-free commonMain + Skiko iOS backend):
//   encoded image bytes
//     -> IosFontLoader.bundledFontFamily(...)            (packaged Noto Latin+CJK faces, S4d-26)
//     -> IosWatermarkRenderer.composeOverImage(...)      (decode via IosImageDecoder -> render cell
//                                                          -> composeOverBackground; Skia bakes EXIF)
//     -> IosWatermarkRenderer.encodePng(bitmap:)         (Skia PNG encode)
//     -> Swift Data -> UIImage(data:)                    (display, done in the View)
//
// S4d-31: the render call now goes through `IosWatermarkRenderBridge.renderWatermarkedPng` (an
// iOS-only `@Throws` boundary), so a font/decode/render/encode failure becomes a Swift `catch` →
// `.failure(...)` instead of a fatal Kotlin/Native crash when C5.3 runs.
// S4d-58: XCUITest executes this path via the DEBUG fixture seam and proves preview + Save + Share.
@MainActor
final class WatermarkWorkflow: ObservableObject {

    enum State: Equatable {
        case idle
        case rendering
        case success(pngByteCount: Int, width: Int, height: Int)
        case failure(String)
    }

    /// Save-to-Photos progress for the current result (C5.4 / S4d-29).
    enum SaveState: Equatable {
        case idle
        case saving
        case saved
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    /// Encoded PNG of the watermarked image; non-nil after a successful render.
    @Published private(set) var resultPNG: Data?
    /// Temp `.png` file URL of the current result, for `ShareLink`; nil until a successful render.
    @Published private(set) var resultFileURL: URL?
    /// Save-to-Photos state for the current result.
    @Published private(set) var saveState: SaveState = .idle

    /// Watermark text composed over the photo (Latin + CJK to exercise both packaged faces).
    var watermarkText: String = "EasyWatermark 水印"

    /// Render `imageData` (the encoded bytes of a picked photo) into a watermarked PNG.
    func render(imageData: Data) async {
        state = .rendering
        resultPNG = nil
        resultFileURL = nil
        saveState = .idle

        // Off the main actor: decode + render + encode are CPU/Skia heavy. Only `Data`/`Int` (Sendable)
        // cross the boundary; the Kotlin objects live and die inside the detached task.
        let text = watermarkText
        let outcome = await Task.detached(priority: .userInitiated) {
            WatermarkWorkflow.renderBlocking(imageData: imageData, text: text)
        }.value

        switch outcome {
        case let .success(png, width, height):
            resultPNG = png
            // Stage a temp .png so `ShareLink` can share a real file; a write failure only disables
            // sharing, the in-memory PNG (and Save-to-Photos) still work.
            resultFileURL = try? ImageExport.writeTemporaryPNG(png)
            state = .success(pngByteCount: png.count, width: width, height: height)
        case let .failure(message):
            resultPNG = nil
            resultFileURL = nil
            state = .failure(message)
        }
    }

    /// Save the current result to the photo library (add-only). No-op if there is no result.
    func saveResultToPhotos() async {
        guard let png = resultPNG else { return }
        saveState = .saving
        do {
            try await ImageExport.saveToPhotos(png)
            saveState = .saved
        } catch {
            saveState = .failed(error.localizedDescription)
        }
    }

    /// Surface a pre-render failure (e.g. PhotosPicker returned no transferable data).
    func reportFailure(_ message: String) {
        resultPNG = nil
        resultFileURL = nil
        saveState = .idle
        state = .failure(message)
    }

    private enum Outcome: Sendable {
        case success(Data, Int, Int)
        case failure(String)
    }

    // `nonisolated` so the detached task can run it off the main actor (it touches no actor state).
    //
    // S4d-31: goes through `IosWatermarkRenderBridge.renderWatermarkedPng`, the iOS-only `@Throws`
    // boundary that wraps `bundledFontFamily → composeOverImage → encodePng`. Any font/decode/render/
    // encode failure arrives here as a Swift-catchable error (an `IosRenderException` bridged to
    // `NSError`) instead of a fatal Kotlin/Native crash, and is surfaced as `.failure(...)`.
    private nonisolated static func renderBlocking(imageData: Data, text: String) -> Outcome {
        do {
            // `composeOverImage` (inside the bridge) uses opaque white internally; `tileMode` must be
            // REPEAT or CLAMP (REPEAT is the product tiling). Kotlin default params don't generate Swift
            // overloads, so every argument is passed explicitly.
            let rendered = try IosWatermarkRenderBridge.shared.renderWatermarkedPng(
                imageBytes: imageData.toKotlinByteArray(),
                text: text,
                tileMode: WatermarkTileMode.repeat,
                textSize: 24.0,
                degree: 315.0,
                hGapPercent: 40,
                vGapPercent: 40,
                offsetX: 0.5,
                offsetY: 0.5,
                alpha: 1.0,
                latinFirst: true,
                bundle: Bundle.main
            )
            return .success(rendered.png.toData(), Int(rendered.width), Int(rendered.height))
        } catch {
            return .failure(describe(error))
        }
    }

    /// Concise message for a bridged render failure. A thrown `IosRenderException` bridges to an
    /// `NSError` whose `userInfo["KotlinException"]` holds the original exception (with `.stage` +
    /// `.message`); fall back to `localizedDescription` for anything else.
    private nonisolated static func describe(_ error: Error) -> String {
        let nsError = error as NSError
        if let render = nsError.userInfo["KotlinException"] as? IosRenderException {
            return "[\(render.stage.name)] \(render.message ?? "render failed")"
        }
        return error.localizedDescription
    }
}
