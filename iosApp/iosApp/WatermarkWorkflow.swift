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

    /// Watermark text composed over the photo. S4d-102: now sourced from the shared
    /// `WaterMarkRepository` via `watermarkConfigBridge` (loaded on launch, edited through the shared
    /// `WatermarkConfigEditor`) instead of a Swift-only constant. Default matches the prior constant.
    @Published private(set) var watermarkText: String = "EasyWatermark 水印"

    /// S4d-103: watermark rotation degree, also sourced from the shared `WaterMarkRepository` via
    /// `watermarkConfigBridge` (loaded on launch, edited through `WatermarkConfigEditor.updateDegree`).
    /// Default 315° matches `WaterMark.default.degree` and the prior hardcoded render arg.
    @Published private(set) var watermarkDegree: Float = 315.0

    /// S4d-104: watermark tile mode, also sourced from the shared `WaterMarkRepository` via
    /// `watermarkConfigBridge` (loaded on launch, edited through `WatermarkConfigEditor.updateTileMode`).
    /// Default REPEAT matches `WaterMark.default.tileMode` and the prior hardcoded render arg. The UI
    /// offers only REPEAT and CLAMP (single decal).
    @Published private(set) var watermarkTileMode: WatermarkTileMode = .repeat

    /// S4d-105: watermark opacity as the **normalized render alpha** (0…1) the render bridge expects.
    /// Sourced from the shared `WaterMarkRepository` (stored as a 0…255 byte) via `watermarkConfigBridge`
    /// and edited through `WatermarkConfigEditor.updateAlpha` (percent). Default 1.0 (opaque) matches
    /// `WaterMark.default.alpha == 255` and the prior hardcoded render arg.
    @Published private(set) var watermarkAlpha: Float = 1.0

    /// S4d-107: watermark text color as a packed ARGB `Int32` (matching the Kotlin 32-bit `Int`), sourced
    /// from the shared `WaterMarkRepository` via `watermarkConfigBridge` and edited through
    /// `WatermarkConfigEditor.updateTextColor`. Default `0xFFFFB800` (amber) is `WaterMark.default
    /// .textColor` — an ALIGNMENT: the fresh-install iOS render changes from the prior hardcoded white to
    /// this shared/Android product default.
    @Published private(set) var watermarkColorArgb: Int32 = Int32(bitPattern: 0xFFFFB800)

    /// S4d-102: the single retained iOS watermark-config bridge over the common `WaterMarkRepository`
    /// (the first off-Android consumer of the shared watermark editor). One instance per process
    /// (DataStore forbids a second active store for the same file), mirroring `userConfigBridge`.
    private let watermarkConfigBridge = IosWatermarkConfigBridgeKt.defaultIosWatermarkConfigBridge()

    /// S4d-102: the last picked photo's encoded bytes, kept so a watermark-text edit can re-render the
    /// same image without re-picking. Nil until the first render.
    private var lastImageData: Data?

    /// S4d-82: the single retained iOS UserConfig prefs bridge (S4d-81), over the app's default
    /// `NSDocumentDirectory` store. One instance per process (DataStore forbids a second active store
    /// for the same file), so it is created once here and held for the workflow's lifetime.
    private let userConfigBridge = IosUserConfigBridgeKt.defaultIosUserConfigBridge()
    /// Non-visible link/async-interop witness: the launch-time `currentPreferences()` result (or an
    /// error string). Published only for testability — there is intentionally NO prefs/settings UI.
    @Published private(set) var userConfigWitness: String?

    /// S4d-82: exercise the Swift↔Kotlin bridge once on launch — a read-only `currentPreferences()`
    /// snapshot (writes no prefs). Stores the result/error in `userConfigWitness` for future use.
    func loadUserConfigWitness() async {
        do {
            let prefs = try await userConfigBridge.currentPreferences()
            userConfigWitness = "\(prefs.outputFormat)/\(prefs.compressLevel)"
        } catch {
            userConfigWitness = "userConfig error: \(error.localizedDescription)"
        }
    }

    /// S4d-102: load the persisted watermark text from the shared `WaterMarkRepository` (one-shot
    /// snapshot). On an empty store this returns the repository's default ("EasyWatermark 水印"), so the
    /// visible default is preserved. A read error keeps the current value.
    func loadWatermarkText() async {
        do {
            watermarkText = try await watermarkConfigBridge.currentText()
        } catch {
            // keep the current default; a read failure must not break the editor
        }
    }

    /// S4d-102: persist a new watermark `text` through the shared `WatermarkConfigEditor` use-case, then
    /// re-render the last image (if any) so the preview reflects the edit. A write failure surfaces as a
    /// `.failure` state without changing the persisted value.
    func setWatermarkText(_ text: String) async {
        do {
            try await watermarkConfigBridge.setText(text: text)
            watermarkText = text
            if let data = lastImageData {
                await render(imageData: data)
            }
        } catch {
            state = .failure("Could not save watermark text: \(error.localizedDescription)")
        }
    }

    /// S4d-103: load the persisted rotation degree from the shared `WaterMarkRepository` (one-shot). On
    /// an empty store this returns `WaterMark.default.degree` (315°), preserving the visible default. A
    /// read error keeps the current value.
    func loadWatermarkDegree() async {
        do {
            // A Kotlin `suspend fun` returning a primitive bridges to Swift as a boxed `KotlinFloat`.
            watermarkDegree = try await watermarkConfigBridge.currentDegree().floatValue
        } catch {
            // keep the current default; a read failure must not break the editor
        }
    }

    /// S4d-103: persist a new rotation `degree` through the shared `WatermarkConfigEditor` (clamped
    /// 0..360 by the shared rules), then re-render the last image (if any). A write failure surfaces as
    /// a `.failure` without changing the persisted value.
    func setWatermarkDegree(_ degree: Float) async {
        do {
            try await watermarkConfigBridge.setDegree(degree: degree)
            watermarkDegree = degree
            if let data = lastImageData {
                await render(imageData: data)
            }
        } catch {
            state = .failure("Could not save watermark rotation: \(error.localizedDescription)")
        }
    }

    /// S4d-104: load the persisted tile mode from the shared `WaterMarkRepository` (one-shot). On an
    /// empty store this returns `WaterMark.default.tileMode` (REPEAT), preserving the visible default. A
    /// read error keeps the current value.
    func loadWatermarkTileMode() async {
        do {
            watermarkTileMode = try await watermarkConfigBridge.currentTileMode()
        } catch {
            // keep the current default; a read failure must not break the editor
        }
    }

    /// S4d-104: persist a new tile `mode` through the shared `WatermarkConfigEditor`, then re-render the
    /// last image (if any). A write failure surfaces as a `.failure` without changing the persisted value.
    func setWatermarkTileMode(_ mode: WatermarkTileMode) async {
        do {
            try await watermarkConfigBridge.setTileMode(tileMode: mode)
            watermarkTileMode = mode
            if let data = lastImageData {
                await render(imageData: data)
            }
        } catch {
            state = .failure("Could not save watermark tile mode: \(error.localizedDescription)")
        }
    }

    /// S4d-105: load the persisted alpha **byte** (0…255) from the shared repo (one-shot) and normalize
    /// to 0…1 for rendering. On an empty store this yields 1.0 (`WaterMark.default.alpha == 255`),
    /// preserving the visible default. A read error keeps the current value.
    func loadWatermarkAlpha() async {
        do {
            // A Kotlin `suspend fun` returning a primitive bridges to Swift as a boxed `KotlinInt`.
            let byte = try await watermarkConfigBridge.currentAlphaByte()
            watermarkAlpha = byte.floatValue / 255.0
        } catch {
            // keep the current default; a read failure must not break the editor
        }
    }

    /// S4d-105: persist a new normalized opacity (`normalized` 0…1) by converting to a 0…100 percent and
    /// writing through the shared `WatermarkConfigEditor` (which truncates to a 0…255 byte), then
    /// re-render the last image (if any). A write failure surfaces as `.failure` without persisting.
    func setWatermarkAlpha(_ normalized: Float) async {
        do {
            try await watermarkConfigBridge.setAlphaPercent(percent: normalized * 100.0)
            watermarkAlpha = normalized
            if let data = lastImageData {
                await render(imageData: data)
            }
        } catch {
            state = .failure("Could not save watermark opacity: \(error.localizedDescription)")
        }
    }

    /// S4d-107: load the persisted ARGB text color from the shared repo (one-shot). On an empty store
    /// this yields `0xFFFFB800` (amber, `WaterMark.default.textColor`). A read error keeps the current value.
    func loadWatermarkTextColor() async {
        do {
            // A Kotlin `suspend fun` returning a primitive bridges to Swift as a boxed `KotlinInt`.
            watermarkColorArgb = try await watermarkConfigBridge.currentTextColor().int32Value
        } catch {
            // keep the current default; a read failure must not break the editor
        }
    }

    /// S4d-107: persist a new ARGB text `color` through the shared `WatermarkConfigEditor`, then re-render
    /// the last image (if any). A write failure surfaces as `.failure` without changing the persisted value.
    func setWatermarkTextColor(_ color: Int32) async {
        do {
            try await watermarkConfigBridge.setTextColor(color: color)
            watermarkColorArgb = color
            if let data = lastImageData {
                await render(imageData: data)
            }
        } catch {
            state = .failure("Could not save watermark color: \(error.localizedDescription)")
        }
    }

    /// Render `imageData` (the encoded bytes of a picked photo) into a watermarked PNG.
    func render(imageData: Data) async {
        state = .rendering
        resultPNG = nil
        resultFileURL = nil
        saveState = .idle
        lastImageData = imageData

        // Off the main actor: decode + render + encode are CPU/Skia heavy. Only `Data`/`Int` (Sendable)
        // cross the boundary; the Kotlin objects live and die inside the detached task.
        let text = watermarkText
        let degree = watermarkDegree
        let tileMode = watermarkTileMode
        let alpha = watermarkAlpha
        let colorArgb = watermarkColorArgb
        let outcome = await Task.detached(priority: .userInitiated) {
            WatermarkWorkflow.renderBlocking(imageData: imageData, text: text, degree: degree, tileMode: tileMode, alpha: alpha, colorArgb: colorArgb)
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
    private nonisolated static func renderBlocking(imageData: Data, text: String, degree: Float, tileMode: WatermarkTileMode, alpha: Float, colorArgb: Int32) -> Outcome {
        do {
            // `composeOverImage` (inside the bridge) renders the text in the passed `colorArgb` ARGB
            // color (S4d-107); `tileMode` must be REPEAT or CLAMP (REPEAT is the product tiling). Kotlin
            // default params don't generate Swift overloads, so every argument is passed explicitly.
            let rendered = try IosWatermarkRenderBridge.shared.renderWatermarkedPng(
                imageBytes: imageData.toKotlinByteArray(),
                text: text,
                tileMode: tileMode,
                textSize: 24.0,
                degree: degree,
                hGapPercent: 40,
                vGapPercent: 40,
                offsetX: 0.5,
                offsetY: 0.5,
                alpha: alpha,
                colorArgb: colorArgb,
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
