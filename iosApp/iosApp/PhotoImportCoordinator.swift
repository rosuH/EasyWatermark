import Foundation
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import CoreTransferable
import os

/// Path-first PhotosPicker transfer: copy provider temp → `ewm_import_provisional_*` immediately.
struct ImageFileTransfer: Transferable {
    let fileURL: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .image) { owned in
            SentTransferredFile(owned.fileURL)
        } importing: { received in
            let tempRoot = FileManager.default.temporaryDirectory
            let destination = tempRoot.appendingPathComponent(
                "ewm_import_provisional_" + UUID().uuidString,
                isDirectory: false
            )
            // The Photos provider may revoke this URL when the importing closure returns. Copy it
            // now; Kotlin will make a second durable `ewm_src_*` copy only after the Host accepts.
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: received.file, to: destination)
            return ImageFileTransfer(fileURL: destination)
        }
    }
}

private enum PhotoTransferResult: Sendable {
    case ready(importId: String, provisionalPath: String)
    case failed(importId: String, message: String)
}

/// Bounded-concurrency progressive import coordinator (maximum two transfer operations).
///
/// Swift owns PhotosPickerItem and provisional files. Kotlin owns fixed presentation slots and
/// Session publication. Failed sources retain only the one `PhotosPickerItem` or provisional path
/// needed for a retry, and are released on success, remove, cancel, or generation supersession.
actor PhotoImportCoordinator {
    static let maxConcurrency = 2

    fileprivate struct UncheckedItemProvider: @unchecked Sendable {
        let raw: NSItemProvider
    }

    /// One new picker asset to transfer. Either a SwiftUI item (legacy/tests) or a PHPicker provider.
    struct PickerFileSource: Sendable {
        let assetId: String?
        let pickerItem: PhotosPickerItem?
        fileprivate let boxedProvider: UncheckedItemProvider?

        var itemProvider: NSItemProvider? { boxedProvider?.raw }

        init(assetId: String?, pickerItem: PhotosPickerItem) {
            self.assetId = assetId
            self.pickerItem = pickerItem
            self.boxedProvider = nil
        }

        init(assetId: String?, itemProvider: NSItemProvider) {
            self.assetId = assetId
            self.pickerItem = nil
            self.boxedProvider = UncheckedItemProvider(raw: itemProvider)
        }
    }

    private struct QueuedItem {
        let importId: String
        let assetId: String?
        let item: PhotosPickerItem?
        let itemProvider: UncheckedItemProvider?
    }

    private struct RetrySource {
        var pickerItem: PhotosPickerItem?
        var itemProvider: UncheckedItemProvider?
        var provisionalPath: String?
        var assetId: String?
    }

    struct BatchResult: Sendable {
        let generation: UInt64
        let successCount: Int
        let failureCount: Int
    }

    private let transferLimiter = PhotoTransferLimiter(maximum: maxConcurrency)
    private var activeGeneration: UInt64?
    private var pendingByGeneration: [UInt64: [QueuedItem]] = [:]
    private var retrySourcesByGeneration: [UInt64: [String: RetrySource]] = [:]
    private var removedIdsByGeneration: [UInt64: Set<String>] = [:]
    private var batchRunningGeneration: UInt64?
    /// Live transfer tasks per generation so supersession can cancel Photos loadTransferable work.
    private var transferTasksByGeneration: [UInt64: [IdentifiedTransferTask]] = [:]
    /// Production-linked id registry (same type the progressive harness exercises).
    private var transferHandleRegistry = TransferTaskHandleRegistry()
    private var controlObservers: [NSObjectProtocol] = []
    private var closed = false

    private final class IdentifiedTransferTask: @unchecked Sendable {
        let id: UUID
        let task: Task<PhotoTransferResult, Never>
        init(id: UUID, task: Task<PhotoTransferResult, Never>) {
            self.id = id
            self.task = task
        }
        func cancel() { task.cancel() }
    }

    /// Test/harness visibility into live registered transfer handle counts (no payloads).
    var debugLiveTransferHandleCount: Int { transferHandleRegistry.totalCount }
    func debugTransferHandleCount(generation: UInt64) -> Int {
        transferHandleRegistry.count(generation: generation)
    }

    /// Install Host→Swift live controls exactly once for this SwiftUI root lifecycle.
    func installControlObservers() {
        guard controlObservers.isEmpty, !closed else { return }
        let center = NotificationCenter.default
        controlObservers.append(
            center.addObserver(
                forName: ProgressiveImportNotifications.retryRequested,
                object: nil,
                queue: .main
            ) { [weak self] note in
                guard let command = ProgressiveImportControlCommand(note) else { return }
                Task { await self?.retry(generation: command.generation, importId: command.importId) }
            }
        )
        controlObservers.append(
            center.addObserver(
                forName: ProgressiveImportNotifications.removeRequested,
                object: nil,
                queue: .main
            ) { [weak self] note in
                guard let command = ProgressiveImportControlCommand(note) else { return }
                Task { await self?.remove(generation: command.generation, importId: command.importId) }
            }
        )
        controlObservers.append(
            center.addObserver(
                forName: ProgressiveImportNotifications.prioritizeRequested,
                object: nil,
                queue: .main
            ) { [weak self] note in
                guard let command = ProgressiveImportControlCommand(note) else { return }
                Task { await self?.prioritize(generation: command.generation, importId: command.importId) }
            }
        )
    }

    /// New picker selection invalidates the prior transfer queue immediately, rather than waiting
    /// for the FIFO commit lane to reach the new batch.
    func supersede(with generation: UInt64) async {
        guard !closed else { return }
        if let activeGeneration, activeGeneration != generation {
            await cancelGeneration(activeGeneration)
        }
        activeGeneration = generation
    }

    /// Start one progressive batch. The queue posts fixed slots first, then publishes every Ready
    /// item independently in original order. The first item occupies the first transfer lane alone
    /// before the second lane opens, making the expected preview reach the user first.
    func importBatch(
        items: [PhotosPickerItem],
        generation: UInt64,
        append: Bool,
        prioritizeFirst: Bool
    ) async -> BatchResult {
        let sources = items.map { item in
            PickerFileSource(assetId: item.itemIdentifier, pickerItem: item)
        }
        return await importBatch(
            sources: sources,
            generation: generation,
            append: append,
            prioritizeFirst: prioritizeFirst
        )
    }

    func importBatch(
        sources: [PickerFileSource],
        generation: UInt64,
        append: Bool,
        prioritizeFirst: Bool
    ) async -> BatchResult {
        guard !closed else {
            return BatchResult(generation: generation, successCount: 0, failureCount: sources.count)
        }
        if let activeGeneration, activeGeneration != generation {
            await cancelGeneration(activeGeneration)
        }
        activeGeneration = generation
        let importIds = sources.indices.map { "gen\(generation)-i\($0)" }
        pendingByGeneration[generation] = zip(importIds, sources).map { id, source in
            QueuedItem(
                importId: id,
                assetId: source.assetId,
                item: source.pickerItem,
                itemProvider: source.boxedProvider
            )
        }
        retrySourcesByGeneration[generation] = Dictionary(
            uniqueKeysWithValues: zip(importIds, sources).map { id, source in
                (
                    id,
                    RetrySource(
                        pickerItem: source.pickerItem,
                        itemProvider: source.boxedProvider,
                        provisionalPath: nil,
                        assetId: source.assetId
                    )
                )
            }
        )
        removedIdsByGeneration[generation] = []
        NotificationCenter.default.post(
            name: ProgressiveImportNotifications.begin,
            object: nil,
            userInfo: [
                ProgressiveImportNotifications.Key.generation: generation,
                ProgressiveImportNotifications.Key.importIds: importIds,
                ProgressiveImportNotifications.Key.append: append,
            ]
        )
        PhotoImportMetrics.event("PickerCallback", generation: generation, importId: "batch:\(sources.count)")

        batchRunningGeneration = generation
        let result = await drainInitialQueue(
            generation: generation,
            firstItemAlone: prioritizeFirst
        )
        if activeGeneration == generation {
            batchRunningGeneration = nil
            NotificationCenter.default.post(
                name: ProgressiveImportNotifications.finish,
                object: nil,
                userInfo: [ProgressiveImportNotifications.Key.generation: generation]
            )
        }
        return result
    }

    /// Retry a partial or all-failed item. Kotlin has already moved the visible cell to Pending;
    /// this method either reuses an owned provisional file or re-runs FileRepresentation from the
    /// retained failed item. No source is retained after an accepted publication.
    @discardableResult
    func retry(generation: UInt64, importId: String) async -> Bool {
        guard isLive(generation: generation, importId: importId),
              let source = retrySourcesByGeneration[generation]?[importId] else {
            return false
        }
        PhotoImportMetrics.event("RetryRequested", generation: generation, importId: importId)
        let accepted: Bool
        if let path = source.provisionalPath,
           FileManager.default.fileExists(atPath: path) {
            accepted = await publishProvisional(
                generation: generation,
                importId: importId,
                path: path
            )
        } else if source.pickerItem != nil || source.itemProvider != nil {
            // Register retry transfers so supersede can cancel them (same path as drainInitialQueue).
            let taskId = UUID()
            let task = Task<PhotoTransferResult, Never> {
                await Self.transfer(
                    QueuedItem(
                        importId: importId,
                        assetId: source.assetId,
                        item: source.pickerItem,
                        itemProvider: source.itemProvider
                    ),
                    generation: generation,
                    limiter: transferLimiter
                )
            }
            transferTasksByGeneration[generation, default: []].append(
                IdentifiedTransferTask(id: taskId, task: task)
            )
            transferHandleRegistry.register(id: taskId, generation: generation)
            let transferResult = await task.value
            pruneFinishedTransferTask(id: taskId, generation: generation)
            accepted = await handleTransferResult(transferResult, generation: generation)
        } else {
            postFailure(
                generation: generation,
                importId: importId,
                message: "The selected photo is no longer available"
            )
            accepted = false
        }
        // The initial queue owns the final finish signal while it is active; otherwise a retry is
        // its own bounded operation and must settle the import chrome.
        if batchRunningGeneration != generation, activeGeneration == generation {
            NotificationCenter.default.post(
                name: ProgressiveImportNotifications.finish,
                object: nil,
                userInfo: [ProgressiveImportNotifications.Key.generation: generation]
            )
        }
        return accepted
    }

    /// Drop a failed/pending retry source after Kotlin has committed the corresponding slot removal.
    func remove(generation: UInt64, importId: String) {
        removedIdsByGeneration[generation, default: []].insert(importId)
        pendingByGeneration[generation]?.removeAll { $0.importId == importId }
        if let source = retrySourcesByGeneration[generation]?.removeValue(forKey: importId),
           let path = source.provisionalPath {
            try? FileManager.default.removeItem(atPath: path)
        }
        PhotoImportMetrics.event("Remove", generation: generation, importId: importId)
    }

    /// Reorder only unscheduled work. An already-active Photos transfer cannot safely be preempted.
    func prioritize(generation: UInt64, importId: String) {
        guard activeGeneration == generation,
              var pending = pendingByGeneration[generation],
              let index = pending.firstIndex(where: { $0.importId == importId }) else {
            return
        }
        let preferred = pending.remove(at: index)
        pending.insert(preferred, at: 0)
        pendingByGeneration[generation] = pending
        PhotoImportMetrics.event("Prioritize", generation: generation, importId: importId)
    }

    /// Cancel/supersede cleanup touches only provisional Swift-owned paths, never Session `ewm_src`.
    /// Cancels in-flight transfer tasks and **only** limiter waiters for this generation.
    /// Awaited so waiters resume before a newer [importBatch]/[supersede] enqueues.
    func cancelGeneration(_ generation: UInt64) async {
        if activeGeneration == generation {
            activeGeneration = nil
        }
        batchRunningGeneration = batchRunningGeneration == generation ? nil : batchRunningGeneration
        pendingByGeneration.removeValue(forKey: generation)
        removedIdsByGeneration.removeValue(forKey: generation)
        if let tasks = transferTasksByGeneration.removeValue(forKey: generation) {
            tasks.forEach { $0.cancel() }
        }
        _ = transferHandleRegistry.removeGeneration(generation)

        if let sources = retrySourcesByGeneration.removeValue(forKey: generation) {
            for path in sources.values.compactMap(\.provisionalPath) {
                try? FileManager.default.removeItem(atPath: path)
            }
        }
        await transferLimiter.cancelWaiters(generation: generation)
        NotificationCenter.default.post(
            name: ProgressiveImportNotifications.cancel,
            object: nil,
            userInfo: [ProgressiveImportNotifications.Key.generation: generation]
        )
        PhotoImportMetrics.event("Cancel", generation: generation, importId: "batch")
    }

    func close() {
        guard !closed else { return }
        closed = true
        if let activeGeneration {
            // close is fire-and-forget from SwiftUI dispose; still generation-scoped cancel.
            Task { await cancelGeneration(activeGeneration) }
        }
        let center = NotificationCenter.default
        controlObservers.forEach(center.removeObserver)
        controlObservers.removeAll()
    }

    private func drainInitialQueue(
        generation: UInt64,
        firstItemAlone: Bool
    ) async -> BatchResult {
        var successCount = 0
        var failureCount = 0
        await withTaskGroup(of: (UUID, PhotoTransferResult).self) { group in
            var inFlight = 0

            func enqueue(_ queued: QueuedItem) {
                inFlight += 1
                let taskId = UUID()
                let task = Task<PhotoTransferResult, Never> {
                    await Self.transfer(
                        queued,
                        generation: generation,
                        limiter: transferLimiter
                    )
                }
                transferTasksByGeneration[generation, default: []].append(
                    IdentifiedTransferTask(id: taskId, task: task)
                )
                transferHandleRegistry.register(id: taskId, generation: generation)
                group.addTask {
                    (taskId, await task.value)
                }
            }

            let initialCapacity = firstItemAlone ? 1 : Self.maxConcurrency
            while inFlight < initialCapacity, let queued = takeNext(generation: generation) {
                enqueue(queued)
            }

            while let (taskId, result) = await group.next() {
                inFlight -= 1
                pruneFinishedTransferTask(id: taskId, generation: generation)
                let accepted = await handleTransferResult(result, generation: generation)
                switch result {
                case .ready:
                    if accepted { successCount += 1 } else { failureCount += 1 }
                case .failed:
                    failureCount += 1
                }
                // Superseded: stop enqueueing and cancel remaining group children.
                guard activeGeneration == generation else {
                    group.cancelAll()
                    continue
                }
                while inFlight < Self.maxConcurrency, let queued = takeNext(generation: generation) {
                    enqueue(queued)
                }
            }
        }
        // Keep transferTasksByGeneration for any still-running retries of this generation.
        return BatchResult(
            generation: generation,
            successCount: successCount,
            failureCount: failureCount
        )
    }

    private func takeNext(generation: UInt64) -> QueuedItem? {
        guard activeGeneration == generation, var pending = pendingByGeneration[generation], !pending.isEmpty else {
            return nil
        }
        let next = pending.removeFirst()
        pendingByGeneration[generation] = pending
        return next
    }

    /// Drop one finished transfer handle; leaves any still-running retries of the generation.
    private func pruneFinishedTransferTask(id: UUID, generation: UInt64) {
        guard var list = transferTasksByGeneration[generation] else {
            transferHandleRegistry.prune(id: id, generation: generation)
            return
        }
        list.removeAll { $0.id == id }
        if list.isEmpty {
            transferTasksByGeneration.removeValue(forKey: generation)
        } else {
            transferTasksByGeneration[generation] = list
        }
        transferHandleRegistry.prune(id: id, generation: generation)
    }

    private func handleTransferResult(
        _ result: PhotoTransferResult,
        generation: UInt64
    ) async -> Bool {
        switch result {
        case let .ready(importId, path):
            return await publishProvisional(generation: generation, importId: importId, path: path)
        case let .failed(importId, message):
            guard isLive(generation: generation, importId: importId) else { return false }
            postFailure(generation: generation, importId: importId, message: message)
            return false
        }
    }

    private func publishProvisional(
        generation: UInt64,
        importId: String,
        path: String
    ) async -> Bool {
        guard isLive(generation: generation, importId: importId) else {
            try? FileManager.default.removeItem(atPath: path)
            return false
        }
        retrySourcesByGeneration[generation]?[importId]?.provisionalPath = path
        PhotoImportMetrics.event("ProvisionalReady", generation: generation, importId: importId)
        let accepted = await ProgressiveImportNotifications.postFileReadyAndAwait(
            generation: generation,
            importId: importId,
            path: path,
            assetId: retrySourcesByGeneration[generation]?[importId]?.assetId
        )
        guard isLive(generation: generation, importId: importId) else {
            try? FileManager.default.removeItem(atPath: path)
            return false
        }
        if accepted {
            retrySourcesByGeneration[generation]?.removeValue(forKey: importId)
            try? FileManager.default.removeItem(atPath: path)
            PhotoImportMetrics.event("Published", generation: generation, importId: importId)
            return true
        }
        postFailure(
            generation: generation,
            importId: importId,
            message: "The photo could not be prepared. Tap to retry."
        )
        return false
    }

    private func postFailure(generation: UInt64, importId: String, message: String) {
        guard isLive(generation: generation, importId: importId) else { return }
        NotificationCenter.default.post(
            name: ProgressiveImportNotifications.fileFailed,
            object: nil,
            userInfo: [
                ProgressiveImportNotifications.Key.generation: generation,
                ProgressiveImportNotifications.Key.importId: importId,
                ProgressiveImportNotifications.Key.message: message,
            ]
        )
        PhotoImportMetrics.event("Failed", generation: generation, importId: importId)
    }

    private func isLive(generation: UInt64, importId: String) -> Bool {
        activeGeneration == generation &&
            !(removedIdsByGeneration[generation] ?? []).contains(importId) &&
            retrySourcesByGeneration[generation]?[importId] != nil
    }

    private static func transfer(
        _ queued: QueuedItem,
        generation: UInt64,
        limiter: PhotoTransferLimiter
    ) async -> PhotoTransferResult {
        let acquired = await limiter.acquire(generation: generation)
        if !acquired || Task.isCancelled {
            if acquired { await limiter.release() }
            return .failed(importId: queued.importId, message: "Photo import was cancelled")
        }
        let signpost = PhotoImportMetrics.beginTransfer(generation: generation, importId: queued.importId)
        let result: PhotoTransferResult
        do {
            let fileURL: URL
            if let item = queued.item {
                guard let transfer = try await item.loadTransferable(type: ImageFileTransfer.self) else {
                    result = .failed(
                        importId: queued.importId,
                        message: "Photos did not provide an image file"
                    )
                    PhotoImportMetrics.endTransfer(signpost, generation: generation, importId: queued.importId)
                    await limiter.release()
                    return result
                }
                fileURL = transfer.fileURL
            } else if let provider = queued.itemProvider?.raw {
                fileURL = try await Self.copyProviderFile(provider)
            } else {
                result = .failed(
                    importId: queued.importId,
                    message: "Photos did not provide an image file"
                )
                PhotoImportMetrics.endTransfer(signpost, generation: generation, importId: queued.importId)
                await limiter.release()
                return result
            }
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: fileURL)
                result = .failed(importId: queued.importId, message: "Photo import was cancelled")
            } else {
                result = .ready(importId: queued.importId, provisionalPath: fileURL.path)
            }
        } catch is CancellationError {
            result = .failed(importId: queued.importId, message: "Photo import was cancelled")
        } catch {
            result = .failed(importId: queued.importId, message: error.localizedDescription)
        }
        PhotoImportMetrics.endTransfer(signpost, generation: generation, importId: queued.importId)
        await limiter.release()
        return result
    }

    /// Copy the Photos item provider file to `ewm_import_provisional_*` before the URL is revoked.
    ///
    /// Retain the `Progress` that `loadFileRepresentation` returns until the completion runs.
    /// Dropping it early lets PhotosUI's Progress KVO tear down while a sibling load is still
    /// `addObserver`-ing on the main thread (`PUPhotosFileProviderItemProvider`).
    private static func copyProviderFile(_ provider: NSItemProvider) async throws -> URL {
        let typeIdentifier = provider.registeredTypeIdentifiers.first { identifier in
            UTType(identifier)?.conforms(to: .image) == true
        } ?? UTType.image.identifier
        return try await withCheckedThrowingContinuation { continuation in
            let progressBox = FileRepresentationProgressBox()
            progressBox.progress = provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                defer { progressBox.progress = nil }
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let url else {
                    continuation.resume(
                        throwing: NSError(
                            domain: "me.rosuh.easywatermark",
                            code: 1,
                            userInfo: [NSLocalizedDescriptionKey: "Photos did not provide an image file"]
                        )
                    )
                    return
                }
                let destination = FileManager.default.temporaryDirectory.appendingPathComponent(
                    "ewm_import_provisional_" + UUID().uuidString,
                    isDirectory: false
                )
                do {
                    if FileManager.default.fileExists(atPath: destination.path) {
                        try FileManager.default.removeItem(at: destination)
                    }
                    try FileManager.default.copyItem(at: url, to: destination)
                    continuation.resume(returning: destination)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

/// Keeps `NSItemProvider.loadFileRepresentation` Progress alive across the GCD callback.
private final class FileRepresentationProgressBox: @unchecked Sendable {
    var progress: Progress?
}

private struct ProgressiveImportControlCommand {
    let generation: UInt64
    let importId: String

    init?(_ note: Notification) {
        guard
            let value = note.userInfo?[ProgressiveImportNotifications.Key.generation],
            let generation = Self.uint64(value),
            let importId = note.userInfo?[ProgressiveImportNotifications.Key.importId] as? String,
            !importId.isEmpty
        else {
            return nil
        }
        self.generation = generation
        self.importId = importId
    }

    private static func uint64(_ value: Any) -> UInt64? {
        if let value = value as? UInt64 { return value }
        if let value = value as? NSNumber { return value.uint64Value }
        if let value = value as? String { return UInt64(value) }
        return nil
    }
}

/// Instruments transfer/stage/publication boundaries without retaining photo bytes or IDs beyond
/// the normal debug trace. Xcode Instruments exposes these as the `EasyWatermark.PhotoImport`
/// signpost stream; physical-device latency remains a separate witness gate.
private enum PhotoImportMetrics {
    private static let log = OSLog(subsystem: "me.rosuh.easywatermark", category: "PhotoImport")

    static func beginTransfer(generation: UInt64, importId: String) -> OSSignpostID {
        let id = OSSignpostID(log: log)
        os_signpost(
            .begin,
            log: log,
            name: "PickerTransfer",
            signpostID: id,
            "generation=%{public}llu import=%{public}s",
            generation,
            importId
        )
        return id
    }

    static func endTransfer(_ id: OSSignpostID, generation: UInt64, importId: String) {
        os_signpost(
            .end,
            log: log,
            name: "PickerTransfer",
            signpostID: id,
            "generation=%{public}llu import=%{public}s",
            generation,
            importId
        )
    }

    static func event(_ name: StaticString, generation: UInt64, importId: String) {
        os_signpost(
            .event,
            log: log,
            name: name,
            "generation=%{public}llu import=%{public}s",
            generation,
            importId
        )
    }
}
