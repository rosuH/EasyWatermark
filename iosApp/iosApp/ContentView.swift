import SwiftUI
import PhotosUI
import UIKit
import Shared

// U3: production UI is a **single** Compose product root (`IosProductRootHost`).
// Swift retains only system edges: PHPicker, Share sheet, Save-to-Photos, DEBUG fixtures/witnesses.

/// Holds the production Compose host so photo/icon delivery and share/save can reach it.
/// Services are process-singleton via Kotlin `defaultIosAppServices()` (DataStore one-instance rule).
@MainActor
final class IosProductRootBox: ObservableObject {
    /// Shared with [WatermarkWorkflow] via the same Kotlin singleton factory.
    let services: IosAppServices = IosAppServicesKt.defaultIosAppServices()
    var host: IosProductRootHost?
    weak var viewController: UIViewController?

    func presentShare(path: String) {
        guard let presenter = foregroundPresenter() else { return }
        let url = URL(fileURLWithPath: path)
        let shareSheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let popover = shareSheet.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = presenter.view.bounds
        }
        presenter.present(shareSheet, animated: true)
    }

    /// `UIViewControllerRepresentable` may replace or temporarily detach its child controller during
    /// Compose recomposition. Resolve the visible window root at action time instead of silently losing
    /// Share when the weak child reference is stale.
    private func foregroundPresenter() -> UIViewController? {
        let window = viewController?.viewIfLoaded?.window
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .filter { $0.activationState == .foregroundActive }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
        guard var presenter = window?.rootViewController else { return nil }
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        return presenter
    }
}

/// Single production ComposeUIViewController for Launch + Editor + CMP templates.
private struct SharedComposeProductRoot: UIViewControllerRepresentable {
    @ObservedObject var box: IosProductRootBox
    var onPickPhoto: () -> Void
    var onPickIcon: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        // Kotlin default params are not visible to Swift — pass the process-singleton services.
        let host = IosProductRootHost(
            onPickPhoto: onPickPhoto,
            onPickIcon: onPickIcon,
            onShare: { [weak box] path in
                Task { @MainActor in
                    box?.presentShare(path: path as String)
                }
            },
            // D4: completion must run only after PHPhotoLibrary.performChanges finishes.
            // Kotlin awaits this edge before counting a persisted success (no fire-and-forget ++).
            onSaveToPhotos: { bytes, onComplete in
                Task {
                    do {
                        try await ImageExport.saveToPhotos(bytes.toData())
                        onComplete(true, nil)
                    } catch {
                        onComplete(false, error.localizedDescription)
                    }
                }
            },
            onOpenUrl: { url in
                Task { @MainActor in
                    guard let u = URL(string: url as String) else { return }
                    UIApplication.shared.open(u)
                }
            },
            services: box.services,
        )
        box.host = host
        let vc = host.viewController()
        box.viewController = vc
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        box.viewController = uiViewController
    }
}

#if DEBUG
private struct SharedComposeLaunchShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.launchScreenShellWitness()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private struct SharedComposeGalleryShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.galleryDialogShellWitness()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private struct SharedComposeAboutShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.aboutScreenShellWitness()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private struct SharedComposeEditorShellWitness: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosSharedComposeHost.shared.editorScreenShellWitness()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
#endif

struct ContentView: View {
    /// System-edge failure surface only — watermark config is session-owned in Kotlin.
    @StateObject private var edge = WatermarkWorkflow()
    @StateObject private var productRoot = IosProductRootBox()
    /// Multi-select source photos (Launch + Editor add-more). Icon watermark stays single.
    @State private var pickedItems: [PhotosPickerItem] = []
    @State private var isPhotoPickerPresented = false
    @State private var pickedIconItem: PhotosPickerItem?
    @State private var isIconPickerPresented = false
    /// Serial photo-batch commit lane (generation + TOCTOU-safe stage). Issue 26 H2 / review F1.
    @State private var photoCommitSerial = PhotosPickerCommitSerial()
    /// Serial icon-picker commit lane (same rule as source batches).
    @State private var iconCommitSerial = PhotosPickerCommitSerial()

#if DEBUG
    private var showSharedComposeWitnesses: Bool {
        ProcessInfo.processInfo.arguments.contains("-sharedComposeWitnesses")
    }

    private func shouldShowSharedComposeWitness(_ name: String) -> Bool {
        let arguments = ProcessInfo.processInfo.arguments
        guard showSharedComposeWitnesses else { return false }
        guard let index = arguments.firstIndex(of: "-sharedComposeWitness"),
              arguments.indices.contains(index + 1) else {
            return true
        }
        return arguments[index + 1] == name
    }

    @ViewBuilder
    private var sharedComposeWitnessSurface: some View {
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

    /// Production path: one CMP product root + system PhotosPicker edges only (no SwiftUI templates).
    /// Olive full-bleed matches DesignEditorBg so status-bar area is not system white.
    private var productBackground: Color {
        Color(red: 0x26 / 255.0, green: 0x26 / 255.0, blue: 0x11 / 255.0)
    }

    @ViewBuilder
    private var productionContent: some View {
        let root = SharedComposeProductRoot(
            box: productRoot,
            onPickPhoto: { isPhotoPickerPresented = true },
            onPickIcon: { isIconPickerPresented = true },
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(productBackground)
        .ignoresSafeArea()
        .accessibilityIdentifier("sharedComposeProductRoot")

        // iOS 17+: request the selected asset's current encoding on both picker edges so
        // loadTransferable does not silently receive a derived/compatible representation (issue 26 H1).
        // iOS 16 deployment keeps the pre-encoding API shape; runtime targets for C4 are 17+.
        if #available(iOS 17.0, *) {
            root
                .photosPicker(
                    isPresented: $isPhotoPickerPresented,
                    selection: $pickedItems,
                    maxSelectionCount: 50,
                    matching: .images,
                    preferredItemEncoding: .current,
                    photoLibrary: .shared(),
                )
                .photosPicker(
                    isPresented: $isIconPickerPresented,
                    selection: $pickedIconItem,
                    matching: .images,
                    preferredItemEncoding: .current,
                    photoLibrary: .shared(),
                )
        } else {
            root
                .photosPicker(
                    isPresented: $isPhotoPickerPresented,
                    selection: $pickedItems,
                    maxSelectionCount: 50,
                    matching: .images,
                    photoLibrary: .shared(),
                )
                .photosPicker(
                    isPresented: $isIconPickerPresented,
                    selection: $pickedIconItem,
                    matching: .images,
                    photoLibrary: .shared(),
                )
        }
    }

    var body: some View {
        Group {
#if DEBUG
            if showSharedComposeWitnesses {
                sharedComposeWitnessSurface
            } else {
                productionContent
            }
#else
            productionContent
#endif
        }
        // iOS 16-compatible onChange (single-parameter); multi PhotosPicker selection batch.
        // F6: freeze generation + append intent synchronously at the selection event, then load async.
        .onChange(of: pickedItems) { newItems in
            guard !newItems.isEmpty else { return }
            let batch = newItems
            let generation = photoCommitSerial.beginGeneration(edge: .photo)
            let frozenAppend = productRoot.host?.isInEditor() ?? false
            // Clear so re-selecting the same set can fire again.
            pickedItems = []
            Task {
                await loadPhotos(
                    batch,
                    generation: generation,
                    frozenAppend: frozenAppend,
                )
            }
        }
        // F6: icon generation frozen at selection event (not inside a later-scheduled task body start).
        .onChange(of: pickedIconItem) { newItem in
            guard let item = newItem else { return }
            let generation = iconCommitSerial.beginGeneration(edge: .icon)
            pickedIconItem = nil
            Task { await loadIcon(item, generation: generation) }
        }
        .task { await runUITestFixtureIfRequested() }
        .task { await edge.loadUserConfigWitness() }
    }

#if DEBUG
    /// UI-test fixture seam (S4d-58 / E14): bypass PHPicker cell selection only; real session export.
    private func runUITestFixtureIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-uiTestFixtureImage") else { return }
        guard pickedItems.isEmpty else { return }
        // Host is created in makeUIViewController; wait briefly if first frame not ready.
        for _ in 0..<50 {
            if productRoot.host != nil { break }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        guard let host = productRoot.host else {
            edge.reportFailure("UI-test fixture: product root host not ready")
            return
        }
        guard let data = Self.makeFixturePNG() else {
            edge.reportFailure("UI-test fixture image generation failed")
            return
        }
        do {
            let fixtureGen = IosPickGenerationGate.shared.nextPhotoGeneration()
            try await host.deliverPickedPhotoAndAwait(
                bytes: data.toKotlinByteArray(),
                append: false,
                renderPreview: true,
                pickGeneration: fixtureGen,
            )
        } catch {
            edge.reportFailure(error.localizedDescription)
        }
    }

    private static func makeFixturePNG() -> Data? {
        let size = CGSize(width: 240, height: 160)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.systemTeal.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.systemOrange.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 120, height: 80))
            UIColor.white.setFill(); ctx.fill(CGRect(x: 120, y: 80, width: 120, height: 80))
        }
        return image.pngData()
    }
#else
    private func runUITestFixtureIfRequested() async {}
#endif

    /// Load selected photos.
    ///
    /// UX sequence (deliberate):
    /// 1. Jump to editor shell immediately (no wait on picker IO).
    /// 2. Load **all** `PhotosPickerItem` bytes first (preserve order).
    /// 3. Stage **once** as a single batch so the filmstrip appears complete —
    ///    never grow 1→2→N while the user flings (that caused snap-back / refresh).
    /// 4. Kotlin host prefetches filmstrip thumbs + first preview asynchronously.
    ///
    /// Late async batches: early drop via shouldDeliver; mutating stage only via
    /// [PhotosPickerCommitSerial.commitIfNewest] (F5 one-in-flight FIFO + MainActor mutation).
    ///
    /// - Parameter generation: frozen at selection-change edge (F6), not at Task start.
    /// - Parameter frozenAppend: in-editor intent frozen with that same selection event.
    private func loadPhotos(
        _ items: [PhotosPickerItem],
        generation: UInt64,
        frozenAppend: Bool,
    ) async {
        guard let host = productRoot.host else {
            edge.reportFailure("Product root host not ready")
            return
        }
        // 1) Show editor shell before any loadTransferable / decode work.
        if !frozenAppend {
            await MainActor.run {
                host.showEditorShellImmediately()
            }
        }

        // 2) Load every payload before staging (parallel, order restored by index). Off-main OK.
        let payloads: [KotlinByteArray] = await withTaskGroup(
            of: (Int, KotlinByteArray?).self,
            returning: [KotlinByteArray].self
        ) { group in
            for (index, item) in items.enumerated() {
                group.addTask {
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self) else {
                            return (index, nil)
                        }
                        return (index, data.toKotlinByteArray())
                    } catch {
                        return (index, nil)
                    }
                }
            }
            var byIndex: [(Int, KotlinByteArray)] = []
            byIndex.reserveCapacity(items.count)
            for await (index, bytes) in group {
                if let bytes {
                    byIndex.append((index, bytes))
                } else {
                    await MainActor.run {
                        edge.reportFailure("Photo picker returned no image data for item \(index)")
                    }
                }
            }
            return byIndex.sorted { $0.0 < $1.0 }.map { $0.1 }
        }

        // Empty/failed batches must not resurrect an older generation (S2).
        guard !payloads.isEmpty else { return }
        // Cheap early drop (non-mutating). Authoritative gate is commitIfNewest below.
        let latestAfterLoad = await MainActor.run { photoCommitSerial.currentLatest() }
        guard PhotosPickerBatchGate.shouldDeliver(
            candidate: generation,
            latest: latestAfterLoad,
        ) else { return }

        // 3) FIFO one-in-flight EnterEditor commit on MainActor (F5).
        // F9: if a newer selection cancelled this generation, commitIfNewest no-ops.
        do {
            try await photoCommitSerial.commitIfNewest(generation: generation) {
                try Task.checkCancellation()
                guard photoCommitSerial.isCurrent(generation) else { return }
                try await host.deliverPickedPhotosBatch(
                    images: payloads,
                    append: frozenAppend,
                    // Fresh pick: raster first image. Add-more: keep current preview (focus preserved).
                    renderPreview: !frozenAppend,
                    pickGeneration: Int64(generation),
                )
                try Task.checkCancellation()
            }
        } catch is CancellationError {
            // Superseded by a newer photo selection (including empty/failed G2) — not a user error.
        } catch {
            // F11: Kotlin StalePickGenerationException surfaces as NSError — treat as supersession.
            let ns = error as NSError
            if ns.localizedDescription.contains("stale pick generation") {
                return
            }
            await MainActor.run {
                edge.reportFailure(error.localizedDescription)
            }
        }
    }

    private func loadIcon(_ item: PhotosPickerItem, generation: UInt64) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                await MainActor.run {
                    edge.reportFailure("Icon picker returned no image data")
                }
                return
            }
            let latestAfterLoad = await MainActor.run { iconCommitSerial.currentLatest() }
            guard PhotosPickerBatchGate.shouldDeliver(
                candidate: generation,
                latest: latestAfterLoad,
            ) else { return }
            guard let host = await MainActor.run(body: { productRoot.host }) else {
                await MainActor.run {
                    edge.reportFailure("Product root host not ready")
                }
                return
            }
            try await iconCommitSerial.commitIfNewest(generation: generation) {
                try Task.checkCancellation()
                guard iconCommitSerial.isCurrent(generation) else { return }
                try await host.deliverIconBytesAndAwait(
                    bytes: data.toKotlinByteArray(),
                    pickGeneration: Int64(generation),
                )
                try Task.checkCancellation()
            }
        } catch is CancellationError {
            // Superseded by a newer icon selection — not a user error.
        } catch {
            await MainActor.run {
                edge.reportFailure(error.localizedDescription)
            }
        }
    }
}
