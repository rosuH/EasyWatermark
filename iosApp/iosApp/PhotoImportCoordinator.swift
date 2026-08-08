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

    private struct QueuedItem {
        let importId: String
        let item: PhotosPickerItem
    }

    private struct RetrySource {
        var pickerItem: PhotosPickerItem?
        var provisionalPath: String?
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
        guard !closed else {
            return BatchResult(generation: generation, successCount: 0, failureCount: items.count)
        }
        if let activeGeneration, activeGeneration != generation {
            await cancelGeneration(activeGeneration)
        }
        activeGeneration = generation
        let importIds = items.indices.map { "gen\(generation)-i\($0)" }
        pendingByGeneration[generation] = zip(importIds, items).map { QueuedItem(importId: $0, item: $1) }
        retrySourcesByGeneration[generation] = Dictionary(
            uniqueKeysWithValues: zip(importIds, items).map {
                ($0, RetrySource(pickerItem: $1, provisionalPath: nil))
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
        PhotoImportMetrics.event("PickerCallback", generation: generation, importId: "batch:\(items.count)")

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
        } else if let item = source.pickerItem {
            // Register retry transfers so supersede can cancel them (same path as drainInitialQueue).
            let taskId = UUID()
            let task = Task<PhotoTransferResult, Never> {
                let value = await Self.transfer(
                    QueuedItem(importId: importId, item: item),
                    generation: generation,
                    limiter: transferLimiter
                )
                await pruneFinishedTransferTask(id: taskId, generation: generation)
                return value
            }
            transferTasksByGeneration[generation, default: []].append(
                IdentifiedTransferTask(id: taskId, task: task)
            )
            transferHandleRegistry.register(id: taskId, generation: generation)
            let transferResult = await task.value
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
        await withTaskGroup(of: PhotoTransferResult.self) { group in
            var inFlight = 0

            func enqueue(_ queued: QueuedItem) {
                inFlight += 1
                let taskId = UUID()
                let task = Task<PhotoTransferResult, Never> {
                    let value = await Self.transfer(
                        queued,
                        generation: generation,
                        limiter: transferLimiter
                    )
                    // Individual prune: drop this handle after completion so the map does not grow
                    // unbounded; retries stay registered until they finish or supersede cancels them.
                    await pruneFinishedTransferTask(id: taskId, generation: generation)
                    return value
                }
                transferTasksByGeneration[generation, default: []].append(
                    IdentifiedTransferTask(id: taskId, task: task)
                )
                transferHandleRegistry.register(id: taskId, generation: generation)
                group.addTask {
                    await task.value
                }
            }

            let initialCapacity = firstItemAlone ? 1 : Self.maxConcurrency
            while inFlight < initialCapacity, let queued = takeNext(generation: generation) {
                enqueue(queued)
            }

            while let result = await group.next() {
                inFlight -= 1
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
            path: path
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
            guard let transfer = try await queued.item.loadTransferable(type: ImageFileTransfer.self) else {
                result = .failed(
                    importId: queued.importId,
                    message: "Photos did not provide an image file"
                )
                PhotoImportMetrics.endTransfer(signpost, generation: generation, importId: queued.importId)
                await limiter.release()
                return result
            }
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: transfer.fileURL)
                result = .failed(importId: queued.importId, message: "Photo import was cancelled")
            } else {
                result = .ready(importId: queued.importId, provisionalPath: transfer.fileURL.path)
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
