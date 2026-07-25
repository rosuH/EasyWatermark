import Foundation
import Shared

/// Pure decision helper for iOS PhotosPicker batch generations (issue 26 / C4.4R.S1).
enum PhotosPickerBatchGate {
    static func beginGeneration(latest: UInt64) -> UInt64 {
        latest &+ 1
    }

    static func shouldDeliver(candidate: UInt64, latest: UInt64) -> Bool {
        candidate == latest
    }

    static func shouldBeginCommit(
        candidate: UInt64,
        latest: UInt64,
        highestPublished: UInt64
    ) -> Bool {
        candidate == latest && candidate > highestPublished
    }
}

/// MainActor FIFO / one-in-flight commit lane for one picker edge.
///
/// **F14:** Generation tokens are issued solely by Kotlin [IosPickGenerationGate]
/// (`nextPhotoGeneration` / `nextIconGeneration`). This class only stores the issued value for
/// FIFO/cancel bookkeeping — it never invents a parallel counter that can restart at zero.
@MainActor
final class PhotosPickerCommitSerial {
    private var latest: UInt64 = 0
    private var highestPublished: UInt64 = 0
    private var commitTail: Task<Void, Never>?
    private var inFlight: Task<Void, Error>?

    enum PickEdge {
        case photo
        case icon
    }

    /// Issue next generation from Kotlin (process-wide) and cancel any older in-flight commit.
    @discardableResult
    func beginGeneration(edge: PickEdge = .photo) -> UInt64 {
        let issued: Int64
        switch edge {
        case .photo:
            issued = IosPickGenerationGate.shared.nextPhotoGeneration()
        case .icon:
            issued = IosPickGenerationGate.shared.nextIconGeneration()
        }
        let next = UInt64(issued)
        latest = next
        inFlight?.cancel()
        return next
    }

    func currentLatest() -> UInt64 { latest }

    func isCurrent(_ generation: UInt64) -> Bool {
        generation == latest
    }

    func commitIfNewest(
        generation: UInt64,
        body: @escaping @MainActor () async throws -> Void
    ) async throws {
        let previous = commitTail
        let task = Task<Void, Error> { @MainActor in
            _ = await previous?.result
            try Task.checkCancellation()
            guard PhotosPickerBatchGate.shouldBeginCommit(
                candidate: generation,
                latest: self.latest,
                highestPublished: self.highestPublished
            ) else { return }
            try Task.checkCancellation()
            guard self.isCurrent(generation) else { return }
            try await body()
            try Task.checkCancellation()
            if self.isCurrent(generation) {
                self.highestPublished = generation
            }
        }
        inFlight = task
        commitTail = Task { @MainActor in
            _ = await task.result
        }
        do {
            try await task.value
        } catch is CancellationError {
            return
        }
    }
}
