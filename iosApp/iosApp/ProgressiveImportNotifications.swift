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
    }

    /// Posts one owned provisional path and returns `true` only after the Host has adopted it and
    /// published the Ready-only Session selection. Timeout/cancellation are explicitly false.
    @MainActor
    static func postFileReadyAndAwait(
        generation: UInt64,
        importId: String,
        path: String,
        timeoutSeconds: TimeInterval = 30
    ) async -> Bool {
        let waiter = FileReadyAckWaiter(
            generation: generation,
            importId: importId,
            path: path,
            timeoutSeconds: timeoutSeconds
        )
        return await waiter.wait()
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
        timeoutSeconds: TimeInterval
    ) {
        self.generation = generation
        self.importId = importId
        self.path = path
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
                NotificationCenter.default.post(
                    name: ProgressiveImportNotifications.fileReady,
                    object: nil,
                    userInfo: [
                        ProgressiveImportNotifications.Key.generation: generation,
                        ProgressiveImportNotifications.Key.importId: importId,
                        ProgressiveImportNotifications.Key.path: path,
                        ProgressiveImportNotifications.Key.requestId: requestId,
                    ]
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
