import Foundation

/// Production-linked transfer-task id registry used by [PhotoImportCoordinator].
///
/// Tracks live transfer handles by generation so supersession can cancel them and finished
/// transfers can be pruned individually without retaining an ever-growing generation array.
/// The progressive harness compiles this same source file to prove prune/register behavior.
struct TransferTaskHandleRegistry {
    private var idsByGeneration: [UInt64: [UUID]] = [:]

    mutating func register(id: UUID, generation: UInt64) {
        idsByGeneration[generation, default: []].append(id)
    }

    /// Drop one finished handle; leave any still-running retries of the generation.
    mutating func prune(id: UUID, generation: UInt64) {
        guard var list = idsByGeneration[generation] else { return }
        list.removeAll { $0 == id }
        if list.isEmpty {
            idsByGeneration.removeValue(forKey: generation)
        } else {
            idsByGeneration[generation] = list
        }
    }

    mutating func removeGeneration(_ generation: UInt64) -> [UUID] {
        idsByGeneration.removeValue(forKey: generation) ?? []
    }

    func count(generation: UInt64) -> Int {
        idsByGeneration[generation]?.count ?? 0
    }

    var totalCount: Int {
        idsByGeneration.values.reduce(0) { $0 + $1.count }
    }

    func contains(id: UUID, generation: UInt64) -> Bool {
        idsByGeneration[generation]?.contains(id) == true
    }
}
