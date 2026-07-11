import SwiftUI
import PhotosUI
import UIKit
import Shared

// C5.4 (S4d-27/29/58): a user picks a photo with `PhotosPicker`, the app loads the encoded bytes, and
// `WatermarkWorkflow` runs the `:shared` render bridge to produce a watermarked PNG, shown via
// the shared CMP preview host. Export is a `ShareLink` (system share sheet over a temp .png) plus Save to Photos.
// S4d-58 proves render + export via the DEBUG-only fixture seam below; real PHPicker cell selection is
// the only step still blocked by Xcode-27-beta / iOS-27 system UI automation.
private struct SharedComposeWatermarkPreview: UIViewControllerRepresentable {
    let png: Data
    let status: String

    final class Coordinator {
        let host = IosWatermarkPreviewHost()
        private var lastPNG: Data?
        private var lastStatus: String?

        func update(png: Data, status: String) {
            guard png != lastPNG || status != lastStatus else { return }
            host.update(png: png.toKotlinByteArray(), status: status)
            lastPNG = png
            lastStatus = status
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.update(png: png, status: status)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.update(png: png, status: status)
    }
}

private struct SharedComposeTileModeControl: UIViewControllerRepresentable {
    let mode: WatermarkTileMode
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkTileModeHost(onValueChange: { [weak self] selectedMode in
            self?.setTileMode(selectedMode)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setTileMode(_ mode: WatermarkTileMode) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTileMode(mode)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(mode: mode)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(mode: mode)
    }
}

private struct SharedComposeTextPaintStyleControl: UIViewControllerRepresentable {
    let styleKey: Int32
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosTextPaintStyleHost(onValueChange: { [weak self] selectedStyle in
            self?.setTextPaintStyle(selectedStyle)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setTextPaintStyle(_ style: TextPaintStyle) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextStyle(style.serializeKey())
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(style: TextPaintStyle.companion.obtainSealedClass(key: styleKey))
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(style: TextPaintStyle.companion.obtainSealedClass(key: styleKey))
    }
}

private struct SharedComposeTextTypefaceControl: UIViewControllerRepresentable {
    let typefaceKey: Int32
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosTextTypefaceHost(onValueChange: { [weak self] selectedTypeface in
            self?.setTextTypeface(selectedTypeface)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setTextTypeface(_ typeface: TextTypeface) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTypeface(typeface.serializeKey())
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(typeface: TextTypeface.companion.obtainSealedClass(key: typefaceKey))
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(typeface: TextTypeface.companion.obtainSealedClass(key: typefaceKey))
    }
}

private func textTypefaceAccessibilityLabel(for key: Int32) -> String {
    switch key {
    case 1:
        return "Typeface Italic"
    case 2:
        return "Typeface Bold"
    case 3:
        return "Typeface BoldItalic"
    default:
        return "Typeface Normal"
    }
}

private struct SharedComposeTextSizeControl: UIViewControllerRepresentable {
    let textSize: Float
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosTextSizeSliderHost(onValueChangeFinished: { [weak self] size in
            self?.setTextSize(size.floatValue)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setTextSize(_ size: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextSize(size)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(textSize: textSize)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(textSize: textSize)
    }
}

private struct SharedComposeWatermarkDegreeControl: UIViewControllerRepresentable {
    let degree: Float
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkDegreeSliderHost(onValueChangeFinished: { [weak self] degree in
            self?.setWatermarkDegree(degree.floatValue)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkDegree(_ degree: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkDegree(degree)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(degree: degree)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(degree: degree)
    }
}

private struct SharedComposeWatermarkAlphaControl: UIViewControllerRepresentable {
    let alpha: Float
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkAlphaSliderHost(onValueChangeFinished: { [weak self] percent in
            self?.setWatermarkAlpha(percent.floatValue / 100.0)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkAlpha(_ alpha: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkAlpha(alpha)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(normalizedAlpha: alpha)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(normalizedAlpha: alpha)
    }
}

private struct SharedComposeWatermarkHorizontalGapControl: UIViewControllerRepresentable {
    let horizontalGap: Int32
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkHorizontalGapSliderHost(onValueChangeFinished: { [weak self] gap in
            self?.setWatermarkHorizontalGap(Int32(gap.floatValue))
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkHorizontalGap(_ gap: Int32) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkHGap(gap)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(horizontalGap: horizontalGap)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(horizontalGap: horizontalGap)
    }
}

#if DEBUG
private struct SharedComposeLaunchShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.launchScreenShellWitness()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

private struct SharedComposeGalleryShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.galleryDialogShellWitness()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

private struct SharedComposeAboutShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.aboutScreenShellWitness()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

private struct SharedComposeEditorShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.editorScreenShellWitness()
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
    /// S4d-110: editable drafts of the watermark h/v gaps; applied to the shared `WaterMarkRepository`.
    @State private var draftVGap: Double = 0

#if DEBUG
    private var showSharedComposeWitnesses: Bool {
        ProcessInfo.processInfo.arguments.contains("-sharedComposeWitnesses")
    }

    private func shouldShowSharedComposeWitness(_ name: String) -> Bool {
        let arguments = ProcessInfo.processInfo.arguments
        guard showSharedComposeWitnesses else { return false }
        guard let index = arguments.firstIndex(of: "-sharedComposeWitness"), arguments.indices.contains(index + 1) else {
            return true
        }
        return arguments[index + 1] == name
    }
#endif

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

            // S4d-333: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeWatermarkDegreeControl(degree: workflow.watermarkDegree, workflow: workflow)
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeWatermarkDegree")
                .accessibilityLabel("Rotation \(Int(workflow.watermarkDegree))")

            // S4d-329: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeTileModeControl(mode: workflow.watermarkTileMode, workflow: workflow)
                .frame(height: 40)
                .accessibilityIdentifier("sharedComposeTileMode")
                .accessibilityLabel(workflow.watermarkTileMode == .clamp ? "Tile mode Single" : "Tile mode Repeat")

            // S4d-334: shared CMP control; Swift retains the normalized-alpha workflow boundary.
            SharedComposeWatermarkAlphaControl(alpha: workflow.watermarkAlpha, workflow: workflow)
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeWatermarkAlpha")
                .accessibilityLabel("Opacity \(Int(workflow.watermarkAlpha * 100))%")

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

            // S4d-332: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeTextSizeControl(textSize: workflow.watermarkTextSize, workflow: workflow)
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeTextSize")
                .accessibilityLabel("Text size \(Int(workflow.watermarkTextSize))")

            // S4d-110: edit the watermark horizontal/vertical gaps through the same shared editor path.
            // Commits on release. Range matches the shared clamp (0…500). Minimal control — not 1:1 UI.
            VStack(spacing: 4) {
                Text("Gaps: H \(workflow.watermarkHGap)  V \(Int(draftVGap))")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkGapLabel")
                // S4d-335: shared CMP control; Swift retains the workflow write and rerender boundary.
                SharedComposeWatermarkHorizontalGapControl(
                    horizontalGap: workflow.watermarkHGap,
                    workflow: workflow,
                )
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeWatermarkHGap")
                .accessibilityLabel("Horizontal gap \(workflow.watermarkHGap)")
                Slider(value: $draftVGap, in: 0...500, step: 1) { editing in
                    if !editing { Task { await workflow.setWatermarkVGap(Int32(draftVGap)) } }
                }
                .accessibilityIdentifier("watermarkVGapSlider")
            }

            // S4d-331: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeTextTypefaceControl(typefaceKey: workflow.watermarkTypefaceKey, workflow: workflow)
                .frame(height: 40)
                .accessibilityIdentifier("sharedComposeTextTypeface")
                .accessibilityLabel(textTypefaceAccessibilityLabel(for: workflow.watermarkTypefaceKey))

            // S4d-330: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeTextPaintStyleControl(styleKey: workflow.watermarkTextStyleKey, workflow: workflow)
                .frame(height: 40)
                .accessibilityIdentifier("sharedComposeTextPaintStyle")
                .accessibilityLabel(workflow.watermarkTextStyleKey == 1 ? "Text style Stroke" : "Text style Fill")

            if let png = workflow.resultPNG {
                SharedComposeWatermarkPreview(png: png, status: renderedPreviewStatus)
                    .frame(height: 360)
                    .accessibilityIdentifier("sharedComposeWatermarkPreview")

                exportBar
                saveStatusView
            } else {
                statusView
            }

            Spacer()

#if DEBUG
                if showSharedComposeWitnesses {
                    if shouldShowSharedComposeWitness("launch") {
                        SharedComposeLaunchShellWitness()
                            .frame(height: 128)
                            .accessibilityIdentifier("sharedComposeLaunchShellWitness")
                    }

                    if shouldShowSharedComposeWitness("gallery") {
                        SharedComposeGalleryShellWitness()
                            .frame(height: 220)
                            .accessibilityIdentifier("sharedComposeGalleryShellWitness")
                    }

                    if shouldShowSharedComposeWitness("about") {
                        SharedComposeAboutShellWitness()
                            .frame(height: 260)
                            .accessibilityIdentifier("sharedComposeAboutShellWitness")
                    }

                    if shouldShowSharedComposeWitness("editor") {
                        SharedComposeEditorShellWitness()
                            .frame(height: 180)
                            .accessibilityIdentifier("sharedComposeEditorShellWitness")
                    }
                }
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
            await workflow.loadWatermarkTileMode()
            await workflow.loadWatermarkAlpha()
            await workflow.loadWatermarkTextColor()
            await workflow.loadWatermarkTextSize()
            await workflow.loadWatermarkGaps()
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

    private var renderedPreviewStatus: String {
        guard case let .success(bytes, width, height) = workflow.state else {
            return "Watermarked preview"
        }
        return "Watermarked \(width)×\(height), PNG \(bytes) B"
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
