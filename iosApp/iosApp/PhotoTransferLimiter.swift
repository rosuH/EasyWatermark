import Foundation

/// Shared across the initial queue and user-initiated retries, so there can never be more than
/// two live PhotosPicker file transfers even when a user retries while the first batch is running.
///
/// Token handoff: [release] either increments `available` or resumes **one** waiter with
/// `acquired=true` (direct ownership transfer — the waiter must not re-check `available`).
/// Waiters are tagged by generation so supersession cancels only matching waiters.
///
/// Package-visible so the progressive harness can exercise the **same** actor production uses.
actor PhotoTransferLimiter {
    private struct Waiter {
        let id: UUID
        let generation: UInt64
        let continuation: CheckedContinuation<Bool, Never>
    }

    private var available: Int
    private var waiters: [Waiter] = []
    private let maximum: Int

    init(maximum: Int) {
        self.maximum = maximum
        available = maximum
    }

    var debugAvailable: Int { available }
    var debugWaiting: Int { waiters.count }

    /// Returns true if a lane was acquired; false if cancelled while waiting (no token held).
    @discardableResult
    func acquire(generation: UInt64) async -> Bool {
        if Task.isCancelled {
            return false
        }
        if available > 0 {
            available -= 1
            return true
        }
        let waiterId = UUID()
        let acquired = await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                waiters.append(Waiter(id: waiterId, generation: generation, continuation: continuation))
            }
        } onCancel: {
            Task { await self.cancelWaiter(id: waiterId) }
        }
        return acquired
    }

    func release() {
        if let waiter = waiters.first {
            waiters.removeFirst()
            // Direct handoff: token ownership moves to the waiter; do not bump `available`.
            waiter.continuation.resume(returning: true)
        } else {
            available += 1
        }
    }

    /// Cancel waiters for one superseded generation only.
    func cancelWaiters(generation: UInt64) {
        let doomed = waiters.filter { $0.generation == generation }
        waiters.removeAll { $0.generation == generation }
        doomed.forEach { $0.continuation.resume(returning: false) }
    }

    private func cancelWaiter(id: UUID) {
        guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
        let waiter = waiters.remove(at: index)
        waiter.continuation.resume(returning: false)
    }
}
