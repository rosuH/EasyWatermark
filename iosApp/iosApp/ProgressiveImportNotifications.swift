import Foundation

/// Zero Shared.framework growth: progressive-import control plane over NotificationCenter.
///
/// Kotlin owns the Session/slot state machine; Swift owns PhotosPickerItem and the provider-file
/// lifetime.  A `fileReady` acknowledgement is keyed by a per-delivery nonce so a late result from
/// a cancelled retry can never accidentally acknowledge a later retry of the same slot.
enum ProgressiveImportNotifications {
    static let begin = Notification.Name("me.rosuh.easywatermark.progressive.begin")
    static let fileReady = Notification.Name("me.rosuh.easywatermark.progressive.fileReady")
    static let fileFailed = Notification.Name("me.rosuh.easywatermark.progressive.fileFailed")
    static let finish = Notification.Name("me.rosuh.easywatermark.progressive.finish")
    static let cancel = Notification.Name("me.rosuh.easywatermark.progressive.cancel")
    static let retry = Notification.Name("me.rosuh.easywatermark.progressive.retry")
    static let remove = Notification.Name("me.rosuh.easywatermark.progressive.remove")
    static let prioritize = Notification.Name("me.rosuh.easywatermark.progressive.prioritize")
    static let fileReadyResult = Notification.Name("me.rosuh.easywatermark.progressive.fileReady.result")

    /// Host → Swift controls. These are deliberately separate from the Swift → Host event names.
    static let retryRequested = Notification.Name("me.rosuh.easywatermark.progressive.retry.requested")
    static let removeRequested = Notification.Name("me.rosuh.easywatermark.progressive.remove.requested")
    static let prioritizeRequested = Notification.Name("me.rosuh.easywatermark.progressive.prioritize.requested")

    enum Key {
        static let generation = "generation"
        static let importId = "importId"
        static let importIds = "importIds"
        static let path = "path"
        static let message = "message"
        static let append = "append"
        static let ok = "ok"
        static let reason = "reason"
        static let requestId = "requestId"
        static let assetId = "assetId"
        static let assetIds = "assetIds"
    }

    static let queryPreselectedAssetIds = Notification.Name(
        "me.rosuh.easywatermark.progressive.queryPreselectedAssetIds"
    )
    static let preselectedAssetIds = Notification.Name(
        "me.rosuh.easywatermark.progressive.preselectedAssetIds"
    )
    static let removeResult = Notification.Name(
        "me.rosuh.easywatermark.progressive.remove.result"
    )

    /// Snapshot current Session Ready paths' asset ids via the Host NC observer (no public API).
    static func currentPreselectedAssetIds() -> [String] {
        var ids: [String] = []
        let observer = NotificationCenter.default.addObserver(
            forName: preselectedAssetIds,
            object: nil,
            queue: nil
        ) { note in
            let raw = note.userInfo?[Key.assetIds]
            if let list = raw as? [String] {
                ids = list
            } else if let list = raw as? [Any] {
                ids = list.compactMap { $0 as? String }
            } else if let list = raw as? NSArray {
                ids = list.compactMap { $0 as? String }
            }
        }
        NotificationCenter.default.post(name: queryPreselectedAssetIds, object: nil)
        NotificationCenter.default.removeObserver(observer)
        return ids.filter { !$0.isEmpty }
    }

    /// Posts one owned provisional path and returns `true` only after the Host has adopted it and
    /// published the Ready-only Session selection. Timeout/cancellation are explicitly false.
    /// `assetId` is omitted from the payload when nil or blank (fixture / no-id path).
    @MainActor
    static func postFileReadyAndAwait(
        generation: UInt64,
        importId: String,
        path: String,
        assetId: String? = nil,
        timeoutSeconds: TimeInterval = 30
    ) async -> Bool {
        let waiter = FileReadyAckWaiter(
            generation: generation,
            importId: importId,
            path: path,
            assetId: assetId,
            timeoutSeconds: timeoutSeconds
        )
        return await waiter.wait()
    }

    /// Ask the Host to drop a Ready slot by Photos asset id. ACK is keyed by requestId.
    @MainActor
    static func postRemoveByAssetIdAndAwait(
        assetId: String,
        timeoutSeconds: TimeInterval = 15
    ) async -> Bool {
        guard !assetId.isEmpty else { return false }
        let requestId = UUID().uuidString
        return await withCheckedContinuation { continuation in
            let box = RemoveAckBox(continuation: continuation)
            box.observer = NotificationCenter.default.addObserver(
                forName: removeResult,
                object: nil,
                queue: .main
            ) { note in
                let info = note.userInfo ?? [:]
                guard let resultId = info[Key.requestId] as? String, resultId == requestId else {
                    return
                }
                let ok: Bool = {
                    if let value = info[Key.ok] as? Bool { return value }
                    if let number = info[Key.ok] as? NSNumber { return number.boolValue }
                    return false
                }()
                box.finish(ok)
            }
            NotificationCenter.default.post(
                name: remove,
                object: nil,
                userInfo: [
                    Key.assetId: assetId,
                    Key.requestId: requestId,
                ]
            )
            let timeout = DispatchWorkItem { box.finish(false) }
            box.timeoutWork = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + timeoutSeconds, execute: timeout)
        }
    }
}

/// Exactly-once ACK for picker-driven remove-by-assetId.
private final class RemoveAckBox: @unchecked Sendable {
    private var finished = false
    private var continuation: CheckedContinuation<Bool, Never>?
    var observer: NSObjectProtocol?
    var timeoutWork: DispatchWorkItem?

    init(continuation: CheckedContinuation<Bool, Never>) {
        self.continuation = continuation
    }

    func finish(_ value: Bool) {
        DispatchQueue.main.async {
            guard !self.finished else { return }
            self.finished = true
            self.timeoutWork?.cancel()
            self.timeoutWork = nil
            if let observer = self.observer {
                NotificationCenter.default.removeObserver(observer)
            }
            self.observer = nil
            let continuation = self.continuation
            self.continuation = nil
            continuation?.resume(returning: value)
        }
    }
}

/// MainActor ownership makes observer removal and continuation completion exactly-once.  It is a
/// class (rather than a local captured continuation) so Task cancellation can synchronously find
/// and remove the right observer without relying on a broad generation/import-id match.
@MainActor
private final class FileReadyAckWaiter {
    private let generation: UInt64
    private let importId: String
    private let path: String
    private let assetId: String?
    private let requestId = UUID().uuidString
    private let timeoutSeconds: TimeInterval

    private var finished = false
    private var observer: NSObjectProtocol?
    private var timeoutWork: DispatchWorkItem?
    private var continuation: CheckedContinuation<Bool, Never>?

    init(
        generation: UInt64,
        importId: String,
        path: String,
        assetId: String?,
        timeoutSeconds: TimeInterval
    ) {
        self.generation = generation
        self.importId = importId
        self.path = path
        self.assetId = assetId
        self.timeoutSeconds = timeoutSeconds
    }

    func wait() async -> Bool {
        await withTaskCancellationHandler(operation: {
            await withCheckedContinuation { continuation in
                guard !finished else {
                    continuation.resume(returning: false)
                    return
                }
                self.continuation = continuation
                observer = NotificationCenter.default.addObserver(
                    forName: ProgressiveImportNotifications.fileReadyResult,
                    object: nil,
                    queue: .main
                ) { [weak self] note in
                    Task { @MainActor [weak self] in
                        self?.receive(note)
                    }
                }
                var userInfo: [String: Any] = [
                    ProgressiveImportNotifications.Key.generation: generation,
                    ProgressiveImportNotifications.Key.importId: importId,
                    ProgressiveImportNotifications.Key.path: path,
                    ProgressiveImportNotifications.Key.requestId: requestId,
                ]
                if let assetId, !assetId.isEmpty {
                    userInfo[ProgressiveImportNotifications.Key.assetId] = assetId
                }
                NotificationCenter.default.post(
                    name: ProgressiveImportNotifications.fileReady,
                    object: nil,
                    userInfo: userInfo
                )
                let timeout = DispatchWorkItem { [weak self] in
                    self?.finish(false)
                }
                timeoutWork = timeout
                DispatchQueue.main.asyncAfter(
                    deadline: .now() + timeoutSeconds,
                    execute: timeout
                )
            }
        }, onCancel: {
            Task { @MainActor [weak self] in
                self?.finish(false)
            }
        })
    }

    private func receive(_ note: Notification) {
        let info = note.userInfo ?? [:]
        // Kotlin may box generation/ok as NSNumber; accept both UInt64 and NSNumber.
        let resultGeneration: UInt64? = {
            if let value = info[ProgressiveImportNotifications.Key.generation] as? UInt64 {
                return value
            }
            if let number = info[ProgressiveImportNotifications.Key.generation] as? NSNumber {
                return number.uint64Value
            }
            return nil
        }()
        guard
            let resultGeneration,
            resultGeneration == generation,
            let resultImportId = info[ProgressiveImportNotifications.Key.importId] as? String,
            resultImportId == importId,
            let resultRequestId = info[ProgressiveImportNotifications.Key.requestId] as? String,
            resultRequestId == requestId
        else {
            return
        }
        let ok: Bool = {
            if let value = info[ProgressiveImportNotifications.Key.ok] as? Bool { return value }
            if let number = info[ProgressiveImportNotifications.Key.ok] as? NSNumber {
                return number.boolValue
            }
            return false
        }()
        finish(ok)
    }

    private func finish(_ value: Bool) {
        guard !finished else { return }
        finished = true
        timeoutWork?.cancel()
        timeoutWork = nil
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        observer = nil
        let continuation = continuation
        self.continuation = nil
        continuation?.resume(returning: value)
    }
}
