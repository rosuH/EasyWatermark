import SwiftUI
import PhotosUI
import UIKit
import Shared

// C5.4 (S4d-27/29/58): a user picks a photo with `PhotosPicker`, the app loads the encoded bytes, and
// `WatermarkWorkflow` runs the `:shared` render bridge to produce a watermarked PNG, shown via
// `UIImage(data:)`. Export is a `ShareLink` (system share sheet over a temp .png) plus Save to Photos.
// S4d-58 proves render + export via the DEBUG-only fixture seam below; real PHPicker cell selection is
// the only step still blocked by Xcode-27-beta / iOS-27 system UI automation.
//
// `WatermarkGeometry().diagonal(...)` is kept as a cheap, eagerly-evaluated `:shared` link witness so
// the framework link is exercised even before any photo is picked.
#if DEBUG
struct SharedComposePreviewWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.editorPreviewFrameWitness()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
#endif

struct ContentView: View {
    @StateObject private var workflow = WatermarkWorkflow()
    @State private var pickedItem: PhotosPickerItem?
    /// S4d-118: the selected ICON for image-watermark mode (separate from the source photo above).
    @State private var pickedIconItem: PhotosPickerItem?
    /// S4d-102: editable draft of the watermark text; applied to the shared `WaterMarkRepository`.
    @State private var draftText: String = ""
    /// S4d-103: editable draft of the watermark rotation degree; applied to the shared `WaterMarkRepository`.
    @State private var draftDegree: Double = 315
    /// S4d-105: editable draft of the watermark opacity (0…1); applied to the shared `WaterMarkRepository`.
    @State private var draftAlpha: Double = 1.0
    /// S4d-109: editable draft of the watermark text size; applied to the shared `WaterMarkRepository`.
    @State private var draftTextSize: Double = 14
    /// S4d-110: editable drafts of the watermark h/v gaps; applied to the shared `WaterMarkRepository`.
    @State private var draftHGap: Double = 0
    @State private var draftVGap: Double = 0

    private let linkWitness = WatermarkGeometry().diagonal(w: 100, h: 100)

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("EasyWatermark iOS")
                    .font(.title2.bold())

            PhotosPicker(selection: $pickedItem, matching: .images, photoLibrary: .shared()) {
                Label("Pick a photo", systemImage: "photo.on.rectangle")
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("pickPhotoButton")

            // S4d-118: pick an ICON for image-watermark mode (separate from the source photo). Selecting an
            // icon persists its bytes via `setIconFromBytes` (flips persisted mode → Image) and re-renders.
            // Minimal affordance — not the final 1:1 editor. Image mode without a readable icon stays a loud
            // `.failure` (S4d-117); it never silently renders text.
            HStack(spacing: 8) {
                PhotosPicker(selection: $pickedIconItem, matching: .images, photoLibrary: .shared()) {
                    Label("Pick icon", systemImage: "seal")
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("pickIconButton")

                Text("Mode: \(workflow.watermarkMarkMode == .image ? "Image" : "Text")")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkModeLabel")

                if let iconData = workflow.iconThumbnail, let iconImage = UIImage(data: iconData) {
                    Image(uiImage: iconImage)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 32, height: 32)
                        .accessibilityIdentifier("watermarkIconThumbnail")
                }
            }

            // S4d-102: edit the watermark text through the shared `WaterMarkRepository` +
            // `WatermarkConfigEditor` (persisted in an iOS DataStore). Minimal control — not the final
            // 1:1 editor. Applying re-renders the current image (if one is picked).
            HStack(spacing: 8) {
                TextField("Watermark text", text: $draftText)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.done)
                    .accessibilityIdentifier("watermarkTextField")
                    .onSubmit { Task { await workflow.setWatermarkText(draftText) } }
                Button("Apply") { Task { await workflow.setWatermarkText(draftText) } }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("applyWatermarkText")
            }

            // S4d-233: minimal Templates UI over the seeded iOS Template Room DB (the no-arg
            // `buildTemplateDatabase()` consumed via `IosTemplateBridge`; on a fresh install the rows are the
            // bundled default templates from S4d-232). Save the current text, apply a template (reuses
            // `setWatermarkText`, which persists + re-renders), or delete one. Minimal — not the final 1:1 editor.
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Templates")
                        .font(.caption.bold())
                    Spacer()
                    Button("Save current") { Task { await workflow.saveCurrentTextAsTemplate() } }
                        .buttonStyle(.bordered)
                        .disabled(draftText.isEmpty)
                        .accessibilityIdentifier("saveTemplateButton")
                }
                ForEach(workflow.templates, id: \.id) { template in
                    HStack {
                        Button(template.content) {
                            Task {
                                await workflow.applyTemplate(template)
                                draftText = workflow.watermarkText
                            }
                        }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("templateRow")
                        Spacer()
                        Button(role: .destructive) {
                            Task { await workflow.deleteTemplate(template) }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Delete template")
                        .accessibilityIdentifier("deleteTemplateButton")
                    }
                }
            }
            .accessibilityIdentifier("templatesSection")

            // S4d-103: edit the watermark rotation degree through the same shared editor path. Minimal
            // control — not the final 1:1 editor. Commits on release (avoids re-rendering mid-drag).
            VStack(spacing: 4) {
                Text("Rotation: \(Int(draftDegree))°")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkDegreeLabel")
                Slider(value: $draftDegree, in: 0...360, step: 1) { editing in
                    if !editing { Task { await workflow.setWatermarkDegree(Float(draftDegree)) } }
                }
                .accessibilityIdentifier("watermarkDegreeSlider")
            }

            // S4d-104: pick the tile mode through the same shared editor path. Only the two product
            // modes common composition supports are exposed: REPEAT (tiled) and CLAMP (single decal).
            // The Picker is bound straight to the workflow (its `set` persists + re-renders), so there is
            // no spurious launch write. Minimal control — not the final 1:1 editor.
            Picker("Tile mode", selection: Binding(
                get: { workflow.watermarkTileMode },
                set: { newMode in Task { await workflow.setWatermarkTileMode(newMode) } }
            )) {
                Text("Repeat").tag(WatermarkTileMode.repeat)
                Text("Single").tag(WatermarkTileMode.clamp)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("watermarkTileModePicker")

            // S4d-105: edit the watermark opacity through the same shared editor path. Commits on release
            // (avoids re-rendering mid-drag). Minimal control — not the final 1:1 editor.
            VStack(spacing: 4) {
                Text("Opacity: \(Int(draftAlpha * 100))%")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkAlphaLabel")
                Slider(value: $draftAlpha, in: 0...1) { editing in
                    if !editing { Task { await workflow.setWatermarkAlpha(Float(draftAlpha)) } }
                }
                .accessibilityIdentifier("watermarkAlphaSlider")
            }

            // S4d-107: pick the text color through the same shared editor path. Minimal preset row only
            // (Amber/White/Black/Red) — not a full color wheel, not the final 1:1 editor. Bound straight
            // to the workflow (its `set` persists + re-renders), so there is no spurious launch write.
            // NOTE: the fresh-install default is amber (#FFB800, the shared default) — an alignment from
            // the prior hardcoded white.
            Picker("Text color", selection: Binding(
                get: { workflow.watermarkColorArgb },
                set: { newColor in Task { await workflow.setWatermarkTextColor(newColor) } }
            )) {
                Text("Amber").tag(Int32(bitPattern: 0xFFFFB800))
                Text("White").tag(Int32(bitPattern: 0xFFFFFFFF))
                Text("Black").tag(Int32(bitPattern: 0xFF000000))
                Text("Red").tag(Int32(bitPattern: 0xFFFF0000))
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("watermarkColorPicker")

            // S4d-109: edit the watermark text size through the same shared editor path. Commits on
            // release (avoids re-rendering mid-drag). Range matches the shared MIN/MAX_TEXT_SIZE (1…100).
            // Minimal control — not the final 1:1 editor.
            VStack(spacing: 4) {
                Text("Size: \(Int(draftTextSize))")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkTextSizeLabel")
                Slider(value: $draftTextSize, in: 1...100, step: 1) { editing in
                    if !editing { Task { await workflow.setWatermarkTextSize(Float(draftTextSize)) } }
                }
                .accessibilityIdentifier("watermarkTextSizeSlider")
            }

            // S4d-110: edit the watermark horizontal/vertical gaps through the same shared editor path.
            // Commits on release. Range matches the shared clamp (0…500). Minimal control — not 1:1 UI.
            VStack(spacing: 4) {
                Text("Gaps: H \(Int(draftHGap))  V \(Int(draftVGap))")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkGapLabel")
                Slider(value: $draftHGap, in: 0...500, step: 1) { editing in
                    if !editing { Task { await workflow.setWatermarkHGap(Int32(draftHGap)) } }
                }
                .accessibilityIdentifier("watermarkHGapSlider")
                Slider(value: $draftVGap, in: 0...500, step: 1) { editing in
                    if !editing { Task { await workflow.setWatermarkVGap(Int32(draftVGap)) } }
                }
                .accessibilityIdentifier("watermarkVGapSlider")
            }

            // S4d-112: pick the text typeface through the same shared editor path. The tags are the
            // storage-id keys (0=Normal,1=Italic,2=Bold,3=BoldItalic). Bound straight to the workflow (its
            // `set` persists + re-renders), so there is no spurious launch write. Default Normal preserves
            // the current output; bold/italic are Compose synthetic (perceptual, not 1:1 with Android).
            Picker("Typeface", selection: Binding(
                get: { workflow.watermarkTypefaceKey },
                set: { newKey in Task { await workflow.setWatermarkTypeface(newKey) } }
            )) {
                Text("Normal").tag(Int32(0))
                Text("Italic").tag(Int32(1))
                Text("Bold").tag(Int32(2))
                Text("BoldItalic").tag(Int32(3))
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("watermarkTypefacePicker")

            // S4d-113: pick the text paint style through the same shared editor path. Tags are the storage-id
            // keys (0=Fill,1=Stroke). Bound straight to the workflow (its `set` persists + re-renders), so
            // there is no spurious launch write. Default Fill preserves the current filled output; Stroke is
            // a Compose hairline outline (perceptual, not 1:1 with Android's Paint.Style.STROKE).
            Picker("Style", selection: Binding(
                get: { workflow.watermarkTextStyleKey },
                set: { newKey in Task { await workflow.setWatermarkTextStyle(newKey) } }
            )) {
                Text("Fill").tag(Int32(0))
                Text("Stroke").tag(Int32(1))
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("watermarkTextStylePicker")

            statusView

            if let png = workflow.resultPNG, let uiImage = UIImage(data: png) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 360)
                    .accessibilityLabel("Watermarked preview")

                exportBar
                saveStatusView
            }

            Spacer()

                Text(":shared linked ✓  geometry.diagonal(100×100) = \(linkWitness)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)

#if DEBUG
                SharedComposePreviewWitness()
                    .frame(height: 96)
                    .accessibilityIdentifier("sharedComposePreviewWitness")
#endif
            }
            .padding()
        }
        // Re-runs whenever a new photo is picked; `.task(id:)` (iOS 15+) avoids the deprecated
        // `onChange(of:perform:)` single-arg form. Cancels/restarts cleanly on reselection.
        .task(id: pickedItem) {
            guard let item = pickedItem else { return }
            await load(item)
        }
        // S4d-118: when an icon is picked, load its bytes and hand them to the workflow (persists +
        // flips mode → Image + re-renders). Cancels/restarts cleanly on reselection.
        .task(id: pickedIconItem) {
            guard let item = pickedIconItem else { return }
            await loadIcon(item)
        }
        // S4d-58 UI-test seam (DEBUG only): see `runUITestFixtureIfRequested`.
        .task { await runUITestFixtureIfRequested() }
        // S4d-82: one-shot read-only exercise of the retained iOS UserConfig prefs bridge on launch
        // (link/async-interop witness; no prefs UI, writes nothing).
        .task { await workflow.loadUserConfigWitness() }
        // S4d-102/S4d-103: load the persisted watermark text + degree from the shared repo on launch and
        // seed the drafts.
        .task {
            await workflow.loadWatermarkText()
            draftText = workflow.watermarkText
            await workflow.loadWatermarkDegree()
            draftDegree = Double(workflow.watermarkDegree)
            await workflow.loadWatermarkTileMode()
            await workflow.loadWatermarkAlpha()
            draftAlpha = Double(workflow.watermarkAlpha)
            await workflow.loadWatermarkTextColor()
            await workflow.loadWatermarkTextSize()
            draftTextSize = Double(workflow.watermarkTextSize)
            await workflow.loadWatermarkGaps()
            draftHGap = Double(workflow.watermarkHGap)
            draftVGap = Double(workflow.watermarkVGap)
            await workflow.loadWatermarkTypeface()
            await workflow.loadWatermarkTextStyle()
            await workflow.loadWatermarkMarkMode()
            await workflow.loadTemplates()
        }
    }

#if DEBUG
    /// UI-test-only seam (S4d-58 / C5.3-d). When the app is launched with `-uiTestFixtureImage 1`
    /// (only XCUITest passes this; a normal launch never does), feed a deterministic in-memory PNG
    /// straight into the REAL `WatermarkWorkflow.render` path — bypassing ONLY the PHPicker selection
    /// step that XCUITest cannot drive on the Xcode-27-beta / iOS-27 picker (S4d-57). This does NOT fake
    /// the preview: the bytes go through `IosWatermarkRenderBridge` (decode → render → encode) exactly
    /// like a picked photo. Compiled out of release builds (`#if DEBUG`).
    private func runUITestFixtureIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-uiTestFixtureImage") else { return }
        guard workflow.state == .idle, pickedItem == nil else { return }
        guard let data = Self.makeFixturePNG() else {
            workflow.reportFailure("UI-test fixture image generation failed")
            return
        }
        await workflow.render(imageData: data)
    }

    /// A small deterministic encoded PNG (no bundled asset) used only by the UI-test fixture seam.
    private static func makeFixturePNG() -> Data? {
        let size = CGSize(width: 240, height: 160)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.systemTeal.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.systemOrange.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 120, height: 80))
            UIColor.white.setFill(); ctx.fill(CGRect(x: 120, y: 80, width: 120, height: 80))
        }
        return image.pngData()
    }
#endif

    /// Share + Save-to-Photos actions for the rendered watermark (shown once a result exists).
    @ViewBuilder
    private var exportBar: some View {
        HStack(spacing: 12) {
            if let url = workflow.resultFileURL {
                ShareLink(item: url) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            }
            Button {
                Task { await workflow.saveResultToPhotos() }
            } label: {
                Label("Save to Photos", systemImage: "square.and.arrow.down")
            }
            .disabled(workflow.saveState == .saving)
        }
        .buttonStyle(.bordered)
    }

    @ViewBuilder
    private var saveStatusView: some View {
        switch workflow.saveState {
        case .idle:
            EmptyView()
        case .saving:
            ProgressView("Saving…")
        case .saved:
            Label("Saved to Photos", systemImage: "checkmark.circle")
                .font(.footnote)
                .foregroundStyle(.green)
        case let .failed(message):
            Text("Save failed: \(message)")
                .font(.footnote)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder
    private var statusView: some View {
        switch workflow.state {
        case .idle:
            Text("Pick a photo to watermark.")
                .foregroundStyle(.secondary)
        case .rendering:
            ProgressView("Rendering…")
        case let .success(bytes, width, height):
            Text("Watermarked \(width)×\(height), PNG \(bytes) B")
                .font(.footnote.monospaced())
                .accessibilityIdentifier("renderStatus")
        case let .failure(message):
            Text("Error: \(message)")
                .font(.footnote)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
    }

    /// Load the picked item's encoded bytes and hand them to the renderer workflow.
    private func load(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                workflow.reportFailure("no image data")
                return
            }
            await workflow.render(imageData: data)
        } catch {
            workflow.reportFailure(error.localizedDescription)
        }
    }

    /// S4d-118: load the picked ICON's encoded bytes and hand them to the workflow, which persists them via
    /// `setIconFromBytes` (→ app-private file + mode = Image) and re-renders. Swift passes bytes only; it
    /// never parses or persists the icon file path.
    private func loadIcon(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                workflow.reportFailure("no icon data")
                return
            }
            await workflow.setWatermarkIcon(data)
        } catch {
            workflow.reportFailure(error.localizedDescription)
        }
    }
}
