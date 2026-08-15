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

    /// E2: cancel export, clear host caches, remove owned temps (idempotent).
    func disposeHost() {
        host?.dispose()
    }

    /// G4: memory-pressure trim — host image caches only; Session selection retained.
    func trimHostCaches() {
        host?.onMemoryWarning()
    }

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

    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
        // E2: product root teardown → host dispose (export cancel, caches, owned temps).
        coordinator.box?.disposeHost()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(box: box)
    }

    final class Coordinator {
        weak var box: IosProductRootBox?
        init(box: IosProductRootBox) { self.box = box }
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
    @State private var isPhotoPickerPresented = false
    @State private var pickedIconItem: PhotosPickerItem?
    @State private var isIconPickerPresented = false
    /// Serial photo-batch commit lane (generation + TOCTOU-safe stage). Issue 26 H2 / review F1.
    @State private var photoCommitSerial = PhotosPickerCommitSerial()
    @State private var photoImportCoordinator = PhotoImportCoordinator()
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
        // G4: trim host image caches on memory warning (keeps Session selection).
        let withMemoryTrim = root.onReceive(
            NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)
        ) { _ in
            productRoot.trimHostCaches()
        }

        // Main-photo picker is UIKit PHPicker so preselectedAssetIdentifiers can bind (ADR-0029 P1).
        // Icon watermark stays SwiftUI PhotosPicker (issue 26 H1 encoding contract).
        let withMainPhotoPicker = withMemoryTrim.sheet(isPresented: $isPhotoPickerPresented) {
            PhotoLibraryPHPicker(
                preselectedAssetIdentifiers: ProgressiveImportNotifications.currentPreselectedAssetIds(),
                onFinish: { results in
                    isPhotoPickerPresented = false
                    handleMainPhotoPickerFinish(results)
                },
            )
            .ignoresSafeArea()
        }

        if #available(iOS 17.0, *) {
            withMainPhotoPicker
                .photosPicker(
                    isPresented: $isIconPickerPresented,
                    selection: $pickedIconItem,
                    matching: .images,
                    preferredItemEncoding: .current,
                    photoLibrary: .shared(),
                )
        } else {
            withMainPhotoPicker
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
        // F6: icon generation frozen at selection event (not inside a later-scheduled task body start).
        .onChange(of: pickedIconItem) { newItem in
            guard let item = newItem else { return }
            let generation = iconCommitSerial.beginGeneration(edge: .icon)
            pickedIconItem = nil
            Task { await loadIcon(item, generation: generation) }
        }
        .task { await runUITestFixtureIfRequested() }
        .task { await edge.loadUserConfigWitness() }
        .task { await photoImportCoordinator.installControlObservers() }
        .onDisappear {
            Task { await photoImportCoordinator.close() }
        }
    }

#if DEBUG
    /// UI-test fixture seam (S4d-58 / E14): bypass PHPicker cell selection only; real session export.
    private func runUITestFixtureIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-uiTestFixtureImage") else { return }
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

    /// PHPicker finished. Unchanged identifier set (including cancel) is a no-op.
    /// Still-selected preselected assets have empty item providers (WWDC21) — reuse owned paths.
    private func handleMainPhotoPickerFinish(_ results: [PHPickerResult]) {
        let oldIds = ProgressiveImportNotifications.currentPreselectedAssetIds()
        let newIds = results.compactMap(\.assetIdentifier)
        let oldSet = Set(oldIds)
        let newSet = Set(newIds)
        if oldSet == newSet {
            return
        }
        let addedIds = newIds.filter { !oldSet.contains($0) }
        let removedIds = oldIds.filter { !newSet.contains($0) }
        let keptIds = newIds.filter { oldSet.contains($0) }
        let addedSources: [PhotoImportCoordinator.PickerFileSource] = results.compactMap { result in
            let id = result.assetIdentifier
            let isAdded: Bool = {
                if let id { return addedIds.contains(id) }
                return !result.itemProvider.registeredTypeIdentifiers.isEmpty
            }()
            guard isAdded else { return nil }
            guard !result.itemProvider.registeredTypeIdentifiers.isEmpty else { return nil }
            return PhotoImportCoordinator.PickerFileSource(
                assetId: id,
                itemProvider: result.itemProvider
            )
        }
        let generation = photoCommitSerial.beginGeneration(edge: .photo)
        let inEditor = productRoot.host?.isInEditor() ?? false
        let frozenAppend = inEditor && !keptIds.isEmpty
        Task {
            await photoImportCoordinator.supersede(with: generation)
            await applyMainPhotoPickerDiff(
                addedSources: addedSources,
                removedIds: removedIds,
                keptIsEmpty: keptIds.isEmpty,
                generation: generation,
                frozenAppend: frozenAppend,
            )
        }
    }

    private func applyMainPhotoPickerDiff(
        addedSources: [PhotoImportCoordinator.PickerFileSource],
        removedIds: [String],
        keptIsEmpty: Bool,
        generation: UInt64,
        frozenAppend: Bool,
    ) async {
        if keptIsEmpty {
            if addedSources.isEmpty {
                await removePickedAssetIds(removedIds)
            } else {
                await loadPhotos(
                    addedSources,
                    generation: generation,
                    frozenAppend: false,
                )
            }
            return
        }
        await removePickedAssetIds(removedIds)
        if !addedSources.isEmpty {
            await loadPhotos(
                addedSources,
                generation: generation,
                frozenAppend: true,
            )
        }
    }

    private func removePickedAssetIds(_ assetIds: [String]) async {
        for assetId in assetIds {
            _ = await ProgressiveImportNotifications.postRemoveByAssetIdAndAwait(assetId: assetId)
        }
    }

    /// Load selected photos (path-first progressive import).
    ///
    /// UX sequence:
    /// 1. Jump to editor shell immediately (no wait on picker IO).
    /// 2. Transfer each asset via FileRepresentation into an app-owned provisional path
    ///    with concurrency 2 and first-item priority (no full-batch Data retention).
    /// 3. Host presents fixed Pending slots immediately; each Ready path is adopted into
    ///    Session independently (order preserved). Filmstrip grows by Ready cells only.
    /// 4. Generation gate + commit serial still drop superseded batches (F5/F6).
    ///
    /// - Parameter generation: frozen at picker-finish edge (F6), not at Task start.
    /// - Parameter frozenAppend: in-editor intent frozen with that same picker-finish event.
    private func loadPhotos(
        _ sources: [PhotoImportCoordinator.PickerFileSource],
        generation: UInt64,
        frozenAppend: Bool,
    ) async {
        guard productRoot.host != nil else {
            edge.reportFailure("Product root host not ready")
            return
        }
        // 1) Show editor shell before any transfer/decode work (progressive slots fill after).
        if !frozenAppend {
            await MainActor.run {
                productRoot.host?.showEditorShellImmediately()
            }
        }

        // 2) Path-first progressive import: FileRepresentation + concurrency 2.
        // Host receives begin/fileReady/fileFailed via NotificationCenter (zero public API growth).
        // No full-batch Data/ByteArray retention (device OOM at ~48 HEIC under the old batch path).
        let latestAfterStart = await MainActor.run { photoCommitSerial.currentLatest() }
        guard PhotosPickerBatchGate.shouldDeliver(
            candidate: generation,
            latest: latestAfterStart,
        ) else {
            await photoImportCoordinator.cancelGeneration(generation)
            return
        }

        do {
            try await photoCommitSerial.commitIfNewest(generation: generation) {
                try Task.checkCancellation()
                guard photoCommitSerial.isCurrent(generation) else {
                    await photoImportCoordinator.cancelGeneration(generation)
                    return
                }
                let result = await photoImportCoordinator.importBatch(
                    sources: sources,
                    generation: generation,
                    append: frozenAppend,
                    prioritizeFirst: true
                )
                try Task.checkCancellation()
                if result.successCount == 0 && result.failureCount > 0 {
                    await MainActor.run {
                        edge.reportFailure("Photo import failed for all selected items")
                    }
                }
            }
        } catch is CancellationError {
            await photoImportCoordinator.cancelGeneration(generation)
        } catch {
            await photoImportCoordinator.cancelGeneration(generation)
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
