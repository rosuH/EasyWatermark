import SwiftUI
import PhotosUI
import UIKit
import Shared

// C5.4 (S4d-27/29/58) + S4d-383 (A5a): launch/system edges unchanged; the editor laundry-list of
// per-control ComposeUIViewController hosts is one production `IosEditorScreenHost` shell.
// PhotosPicker / Templates / Share / Save / `WatermarkWorkflow` stay Swift-owned.
// S4d-58 DEBUG fixture seam still bypasses PHPicker grid-cell selection for XCUITest only.
private struct SharedComposeLaunchScreen: UIViewControllerRepresentable {
    let onPickImage: () -> Void

    final class Coordinator {
        var onPickImage: () -> Void
        lazy var host = IosLaunchScreenHost(onPickImage: { [weak self] in
            self?.onPickImage()
        })

        init(onPickImage: @escaping () -> Void) {
            self.onPickImage = onPickImage
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onPickImage: onPickImage) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.onPickImage = onPickImage
    }
}

/// S4d-383 / A5a: one production ComposeUIViewController for EditorScreenShell + option column.
/// Snapshot plain MainActor values into `update` so the representable stays nonisolated-safe.
private struct SharedComposeEditorScreen: UIViewControllerRepresentable {
    let text: String
    let degree: Float
    let tileMode: WatermarkTileMode
    let alpha: Float
    let colorArgb: Int32
    let textSize: Float
    let hGap: Int32
    let vGap: Int32
    let typefaceKey: Int32
    let textStyleKey: Int32
    let isImageMode: Bool
    let iconBytes: Data?
    let previewPng: Data?
    let statusLine: String
    let canShare: Bool
    let isSaving: Bool
    let onPickIcon: () -> Void
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        weak var viewController: UIViewController?
        var onPickIcon: () -> Void
        var resultFileURL: URL?
        lazy var host = IosEditorScreenHost(
            onPickIcon: { [weak self] in self?.onPickIcon() },
            onTextChange: { [weak self] text in self?.setText(text) },
            onDegreeFinished: { [weak self] value in self?.setDegree(value.floatValue) },
            onTileModeChange: { [weak self] mode in self?.setTileMode(mode) },
            onAlphaFinished: { [weak self] percent in self?.setAlphaPercent(percent.floatValue) },
            onColorSelected: { [weak self] color in self?.setColor(color.int32Value) },
            onTextSizeFinished: { [weak self] size in self?.setTextSize(size.floatValue) },
            onHorizontalGapFinished: { [weak self] gap in self?.setHGap(gap.floatValue) },
            onVerticalGapFinished: { [weak self] gap in self?.setVGap(gap.floatValue) },
            onTypefaceChange: { [weak self] typeface in self?.setTypeface(typeface) },
            onTextStyleChange: { [weak self] style in self?.setTextStyle(style) },
            onShare: { [weak self] in self?.shareResult() },
            onSaveToPhotos: { [weak self] in self?.saveToPhotos() },
        )

        init(workflow: WatermarkWorkflow, onPickIcon: @escaping () -> Void, resultFileURL: URL?) {
            self.workflow = workflow
            self.onPickIcon = onPickIcon
            self.resultFileURL = resultFileURL
        }

        func setText(_ text: String) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkText(text)
            }
        }

        func setDegree(_ degree: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkDegree(degree)
            }
        }

        func setTileMode(_ mode: WatermarkTileMode) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTileMode(mode)
            }
        }

        func setAlphaPercent(_ percent: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkAlpha(percent / 100.0)
            }
        }

        func setColor(_ color: Int32) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextColor(color)
            }
        }

        func setTextSize(_ size: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextSize(size)
            }
        }

        func setHGap(_ gap: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkHGap(Int32(gap.rounded()))
            }
        }

        func setVGap(_ gap: Float) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkVGap(Int32(gap.rounded()))
            }
        }

        func setTypeface(_ typeface: TextTypeface) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTypeface(typeface.serializeKey())
            }
        }

        func setTextStyle(_ style: TextPaintStyle) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextStyle(style.serializeKey())
            }
        }

        /// Same system-edge share path as the retired `SharedComposeSavedOutputActions` host.
        func shareResult() {
            guard let resultFileURL, let viewController else { return }
            let shareSheet = UIActivityViewController(
                activityItems: [resultFileURL],
                applicationActivities: nil,
            )
            if let popover = shareSheet.popoverPresentationController {
                popover.sourceView = viewController.view
                popover.sourceRect = viewController.view.bounds
            }
            viewController.present(shareSheet, animated: true)
        }

        func saveToPhotos() {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.saveResultToPhotos()
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(workflow: workflow, onPickIcon: onPickIcon, resultFileURL: workflow.resultFileURL)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        push(context.coordinator)
        let vc = context.coordinator.host.viewController()
        context.coordinator.viewController = vc
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.onPickIcon = onPickIcon
        context.coordinator.resultFileURL = workflow.resultFileURL
        context.coordinator.viewController = uiViewController
        push(context.coordinator)
    }

    private func push(_ coordinator: Coordinator) {
        let typeface = TextTypeface.companion.obtainSealedClass(key: typefaceKey)
        let style = TextPaintStyle.companion.obtainSealedClass(key: textStyleKey)
        coordinator.host.update(
            text: text,
            degree: degree,
            tileMode: tileMode,
            normalizedAlpha: alpha,
            textColor: colorArgb,
            textSize: textSize,
            horizontalGap: hGap,
            verticalGap: vGap,
            typeface: typeface,
            textStyle: style,
            isImageMode: isImageMode,
            iconBytes: iconBytes?.toKotlinByteArray(),
            previewPng: previewPng?.toKotlinByteArray(),
            statusLine: statusLine,
            hasOutput: previewPng != nil,
            canShare: canShare,
            isSaving: isSaving,
        )
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
    @State private var isPhotoPickerPresented = false
    /// S4d-118: the selected ICON for image-watermark mode (separate from the source photo above).
    @State private var pickedIconItem: PhotosPickerItem?
    @State private var isIconPickerPresented = false
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

    /// Full-screen DEBUG test surface for shell witnesses. Independent of the production
    /// editor/Templates layout so XCUITest can reach named IDs without scrolling past a
    /// fill-height CMP host. Named accessibility identifiers are preserved.
    @ViewBuilder
    private var sharedComposeWitnessSurface: some View {
        // Independently scrollable so a single requested witness is immediately on-screen,
        // and an unfiltered multi-witness dump remains reachable by scrolling this surface alone.
        ScrollView {
            VStack(spacing: 16) {
                if shouldShowSharedComposeWitness("launch") {
                    SharedComposeLaunchShellWitness()
                        .frame(maxWidth: .infinity)
                        .frame(height: 220)
                        .accessibilityIdentifier("sharedComposeLaunchShellWitness")
                }
                if shouldShowSharedComposeWitness("gallery") {
                    SharedComposeGalleryShellWitness()
                        .frame(maxWidth: .infinity)
                        .frame(height: 320)
                        .accessibilityIdentifier("sharedComposeGalleryShellWitness")
                }
                if shouldShowSharedComposeWitness("about") {
                    SharedComposeAboutShellWitness()
                        .frame(maxWidth: .infinity)
                        .frame(height: 360)
                        .accessibilityIdentifier("sharedComposeAboutShellWitness")
                }
                if shouldShowSharedComposeWitness("editor") {
                    SharedComposeEditorShellWitness()
                        .frame(maxWidth: .infinity)
                        .frame(height: 280)
                        .accessibilityIdentifier("sharedComposeEditorShellWitness")
                }
            }
            .frame(maxWidth: .infinity)
            .padding()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
#endif

    private var isShowingLaunchScreen: Bool {
#if DEBUG
        if showSharedComposeWitnesses { return false }
#endif
        guard pickedItem == nil else { return false }
        if case .idle = workflow.state { return true }
        return false
    }

    /// Production launch + editor path (unchanged by the DEBUG witness route).
    @ViewBuilder
    private var productionContent: some View {
        if isShowingLaunchScreen {
            SharedComposeLaunchScreen(onPickImage: { isPhotoPickerPresented = true })
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("sharedComposeLaunchScreen")
                .accessibilityLabel("Pick a photo")
                .photosPicker(
                    isPresented: $isPhotoPickerPresented,
                    selection: $pickedItem,
                    matching: .images,
                    photoLibrary: .shared(),
                )
        } else {
            ZStack(alignment: .topTrailing) {
                // A5a layout: fill-height single CMP host (options scroll inside host) + fixed
                // Templates strip. Launch / Templates content / pickPhoto / tasks stay as before.
                VStack(spacing: 0) {
                    // S4d-383 / A5a: single production CMP editor host (EditorScreenShell + options +
                    // preview + Share/Save). Replaces the per-control host laundry list only.
                    SharedComposeEditorScreen(
                        text: workflow.watermarkText,
                        degree: workflow.watermarkDegree,
                        tileMode: workflow.watermarkTileMode,
                        alpha: workflow.watermarkAlpha,
                        colorArgb: workflow.watermarkColorArgb,
                        textSize: workflow.watermarkTextSize,
                        hGap: workflow.watermarkHGap,
                        vGap: workflow.watermarkVGap,
                        typefaceKey: workflow.watermarkTypefaceKey,
                        textStyleKey: workflow.watermarkTextStyleKey,
                        isImageMode: workflow.watermarkMarkMode == .image,
                        iconBytes: workflow.iconThumbnail,
                        previewPng: workflow.resultPNG,
                        statusLine: editorStatusLine,
                        canShare: workflow.resultFileURL != nil,
                        isSaving: workflow.saveState == .saving,
                        onPickIcon: { isIconPickerPresented = true },
                        workflow: workflow,
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("sharedComposeEditorScreen")
                    .photosPicker(
                        isPresented: $isIconPickerPresented,
                        selection: $pickedIconItem,
                        matching: .images,
                        photoLibrary: .shared(),
                    )

                    // S4d-233: minimal Templates UI over the seeded iOS Template Room DB (the no-arg
                    // `buildTemplateDatabase()` consumed via `IosTemplateBridge`; on a fresh install the rows are the
                    // bundled default templates from S4d-232). Save the current text, apply a template (reuses
                    // `setWatermarkText`, which persists + re-renders), or delete one. Minimal — not the final 1:1 editor.
                    ScrollView {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("Templates")
                                    .font(.caption.bold())
                                    .accessibilityAddTraits(.isHeader)
                                Spacer()
                                // S4d-378: stable production a11y id for XCUITest (semantic locator, not label).
                                // Label stays the visible title "Save current"; action unchanged.
                                Button {
                                    Task { await workflow.saveCurrentTextAsTemplate() }
                                } label: {
                                    Text("Save current")
                                }
                                .buttonStyle(.bordered)
                                .disabled(workflow.watermarkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                .accessibilityIdentifier("saveTemplateButton")
                            }
                            ForEach(workflow.templates, id: \.id) { template in
                                HStack {
                                    Button(template.content) {
                                        Task {
                                            await workflow.applyTemplate(template)
                                        }
                                    }
                                    .buttonStyle(.borderless)
                                    // S4d-378: per-row id so XCUITest can pair Apply/Delete without Y-frame heuristics.
                                    .accessibilityIdentifier("templateRow-\(template.id)")
                                    .accessibilityLabel(template.content)
                                    Spacer()
                                    Button(role: .destructive) {
                                        Task { await workflow.deleteTemplate(template) }
                                    } label: {
                                        Image(systemName: "trash")
                                    }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel("Delete template")
                                    .accessibilityIdentifier("deleteTemplateButton-\(template.id)")
                                }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                    }
                    // Compact strip so EditorScreenShell preview retains usable height on small phones.
                    .frame(maxHeight: 120)
                    // children: .contain keeps Save/row/delete as separate AX elements (not one combined button).
                    // Section id is on the container only — do not rely on it for the Save action.
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("templatesSection")

                    // SwiftUI save confirmation remains outside the CMP host (system-edge status).
                    saveStatusView
                        .padding(.bottom, 4)
                }
                // The shared launch shell owns initial entry. Keep source replacement as a compact
                // SwiftUI system-picker edge without changing the editor's scroll layout.
                PhotosPicker(selection: $pickedItem, matching: .images, photoLibrary: .shared()) {
                    Image(systemName: "photo.on.rectangle")
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("pickPhotoButton")
                .accessibilityLabel("Pick another photo")
                .padding()
            }
        }
    }

    var body: some View {
        Group {
#if DEBUG
            // DEBUG-only witness route: full-screen test surface so shell witnesses are not
            // trapped below the production fill-height editor + Templates strip.
            if showSharedComposeWitnesses {
                sharedComposeWitnessSurface
            } else {
                productionContent
            }
#else
            productionContent
#endif
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
        // S4d-102/S4d-103: load persisted watermark config from the shared repo on launch.
        // S4d-378: text is owned by the CMP host + workflow.watermarkText (no SwiftUI draft field).
        .task {
            await workflow.loadWatermarkText()
            await workflow.loadWatermarkDegree()
            await workflow.loadWatermarkTileMode()
            await workflow.loadWatermarkAlpha()
            await workflow.loadWatermarkTextColor()
            await workflow.loadWatermarkTextSize()
            await workflow.loadWatermarkGaps()
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

    /// Status line for the A5a editor host top bar (same copy as the retired SwiftUI status views).
    private var editorStatusLine: String {
        switch workflow.state {
        case .idle:
            return "Pick a photo to watermark."
        case .rendering:
            return "Rendering…"
        case let .success(bytes, width, height):
            return "Watermarked \(width)×\(height), PNG \(bytes) B"
        case let .failure(message):
            return "Error: \(message)"
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
