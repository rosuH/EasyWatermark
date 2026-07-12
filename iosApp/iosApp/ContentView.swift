import SwiftUI
import PhotosUI
import UIKit
import Shared

// C5.4 (S4d-27/29/58): a user picks a photo with `PhotosPicker`, the app loads the encoded bytes, and
// `WatermarkWorkflow` runs the `:shared` render bridge to produce a watermarked PNG, shown via
// the shared CMP preview host. Export is the shared CMP output action row, which delegates system
// sharing to `UIActivityViewController` over the staged temp PNG plus Save to Photos.
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

private struct SharedComposeIconWatermarkControl: UIViewControllerRepresentable {
    let icon: Data?
    let onPick: () -> Void

    final class Coordinator {
        var onPick: () -> Void
        lazy var host = IosWatermarkIconOptionHost(onPick: { [weak self] in
            self?.onPick()
        })

        init(onPick: @escaping () -> Void) {
            self.onPick = onPick
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(iconBytes: icon?.toKotlinByteArray())
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.onPick = onPick
        context.coordinator.host.update(iconBytes: icon?.toKotlinByteArray())
    }
}

private struct SharedComposeSavedOutputActions: UIViewControllerRepresentable {
    let resultFileURL: URL?
    let isSaving: Bool
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        weak var viewController: UIViewController?
        var resultFileURL: URL?
        lazy var host = IosSavedOutputActionsHost(
            onShare: { [weak self] in self?.shareResult() },
            onSaveToPhotos: { [weak self] in self?.saveResultToPhotos() },
        )

        init(resultFileURL: URL?, workflow: WatermarkWorkflow) {
            self.resultFileURL = resultFileURL
            self.workflow = workflow
        }

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

        func saveResultToPhotos() {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.saveResultToPhotos()
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(resultFileURL: resultFileURL, workflow: workflow)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        let coordinator = context.coordinator
        // Share needs staged temp URL; Save uses in-memory resultPNG (host only when PNG exists).
        coordinator.host.update(
            canShare: resultFileURL != nil,
            isSaving: isSaving,
        )
        let viewController = coordinator.host.viewController()
        coordinator.viewController = viewController
        return viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        let coordinator = context.coordinator
        coordinator.workflow = workflow
        coordinator.resultFileURL = resultFileURL
        coordinator.viewController = uiViewController
        coordinator.host.update(
            canShare: resultFileURL != nil,
            isSaving: isSaving,
        )
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

private struct SharedComposeWatermarkVerticalGapControl: UIViewControllerRepresentable {
    let verticalGap: Int32
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkVerticalGapSliderHost(onValueChangeFinished: { [weak self] gap in
            self?.setWatermarkVerticalGap(Int32(gap.floatValue))
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkVerticalGap(_ gap: Int32) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkVGap(gap)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(verticalGap: verticalGap)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(verticalGap: verticalGap)
    }
}

private struct SharedComposeTextColorControl: UIViewControllerRepresentable {
    let color: Int32
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosWatermarkTextColorHost(onColorSelected: { [weak self] color in
            self?.setWatermarkTextColor(color.int32Value)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkTextColor(_ color: Int32) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkTextColor(color)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(color: color)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(color: color)
    }
}

/// S4d-378: production shared `TextContentOption` host. Swift keeps workflow write + re-render.
private struct SharedComposeTextContentControl: UIViewControllerRepresentable {
    let text: String
    let workflow: WatermarkWorkflow

    final class Coordinator {
        weak var workflow: WatermarkWorkflow?
        lazy var host = IosTextContentOptionHost(onTextChange: { [weak self] next in
            self?.setWatermarkText(next)
        })

        init(workflow: WatermarkWorkflow) {
            self.workflow = workflow
        }

        func setWatermarkText(_ text: String) {
            Task { @MainActor [weak workflow] in
                guard let workflow else { return }
                await workflow.setWatermarkText(text)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(workflow: workflow) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.host.update(text: text)
        return context.coordinator.host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.workflow = workflow
        context.coordinator.host.update(text: text)
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
#endif

    private var isShowingLaunchScreen: Bool {
#if DEBUG
        if showSharedComposeWitnesses { return false }
#endif
        guard pickedItem == nil else { return false }
        if case .idle = workflow.state { return true }
        return false
    }

    var body: some View {
        Group {
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
                    ScrollView {
                        VStack(spacing: 16) {

            // S4d-118: pick an ICON for image-watermark mode (separate from the source photo). Selecting an
            // icon persists its bytes via `setIconFromBytes` (flips persisted mode → Image) and re-renders.
            // Minimal affordance — not the final 1:1 editor. Image mode without a readable icon stays a loud
            // `.failure` (S4d-117); it never silently renders text.
            VStack(spacing: 4) {
                SharedComposeIconWatermarkControl(
                    icon: workflow.iconThumbnail,
                    onPick: { isIconPickerPresented = true },
                )
                .frame(height: 80)
                .accessibilityIdentifier("sharedComposeIconWatermarkOption")
                .accessibilityLabel(workflow.iconThumbnail == nil ? "Watermark icon not selected" : "Watermark icon selected")
                .photosPicker(
                    isPresented: $isIconPickerPresented,
                    selection: $pickedIconItem,
                    matching: .images,
                    photoLibrary: .shared(),
                )
                Text("Mode: \(workflow.watermarkMarkMode == .image ? "Image" : "Text")")
                    .font(.caption)
                    .accessibilityIdentifier("watermarkModeLabel")
            }

            // S4d-378: watermark text via shared CMP `TextContentOption` (ModalBottomSheet + field).
            // Swift retains WatermarkWorkflow write + re-render. Templates stay SwiftUI (not TemplateListSheet).
            SharedComposeTextContentControl(text: workflow.watermarkText, workflow: workflow)
                // Fixed height (like other CMP hosts): minHeight alone lets ComposeUIViewController
                // expand in the ScrollView and push/cover the Templates section.
                .frame(height: 56)
                .accessibilityIdentifier("sharedComposeTextContent")
                .accessibilityLabel("Watermark text \(workflow.watermarkText)")

            // S4d-233: minimal Templates UI over the seeded iOS Template Room DB (the no-arg
            // `buildTemplateDatabase()` consumed via `IosTemplateBridge`; on a fresh install the rows are the
            // bundled default templates from S4d-232). Save the current text, apply a template (reuses
            // `setWatermarkText`, which persists + re-renders), or delete one. Minimal — not the final 1:1 editor.
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
            // children: .contain keeps Save/row/delete as separate AX elements (not one combined button).
            // Section id is on the container only — do not rely on it for the Save action.
            .accessibilityElement(children: .contain)
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

            // S4d-337: shared CMP four-preset palette; Swift retains the workflow write and rerender boundary.
            SharedComposeTextColorControl(color: workflow.watermarkColorArgb, workflow: workflow)
                .frame(height: 40)
                .accessibilityIdentifier("sharedComposeTextColor")
                .accessibilityLabel("Text color \(workflow.watermarkColorArgb)")

            // S4d-332: shared CMP control; Swift keeps the workflow write and rerender boundary.
            SharedComposeTextSizeControl(textSize: workflow.watermarkTextSize, workflow: workflow)
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeTextSize")
                .accessibilityLabel("Text size \(Int(workflow.watermarkTextSize))")

            // S4d-336: shared CMP controls; Swift keeps each workflow write and rerender boundary.
            VStack(spacing: 4) {
                Text("Gaps: H \(workflow.watermarkHGap)  V \(workflow.watermarkVGap)")
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
                SharedComposeWatermarkVerticalGapControl(
                    verticalGap: workflow.watermarkVGap,
                    workflow: workflow,
                )
                .frame(height: 72)
                .accessibilityIdentifier("sharedComposeWatermarkVGap")
                .accessibilityLabel("Vertical gap \(workflow.watermarkVGap)")
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

            // Product order matches the retired exportBar slot: preview, then shared actions.
            // S4d-344: commonMain owns the action row; UIKit remains Share/Photos system UI edge.
            if let png = workflow.resultPNG {
                SharedComposeWatermarkPreview(png: png, status: renderedPreviewStatus)
                    .frame(height: 360)
                    .accessibilityIdentifier("sharedComposeWatermarkPreview")

                SharedComposeSavedOutputActions(
                    resultFileURL: workflow.resultFileURL,
                    isSaving: workflow.saveState == .saving,
                    workflow: workflow,
                )
                .frame(height: 44)
                .accessibilityIdentifier("sharedComposeSavedOutputActions")
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
