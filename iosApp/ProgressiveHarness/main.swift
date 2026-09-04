import Foundation

/// Host-side production-linked harness for [PhotoTransferLimiter] (same actor source as the app).
/// Compiled with `swiftc` against `../iosApp/PhotoTransferLimiter.swift` — no simulator required.
@main
struct ProgressiveHarnessMain {
    static func main() async {
        var failures = 0
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            if ok {
                print("PASS \(name)")
            } else {
                failures += 1
                print("FAIL \(name) \(detail)")
            }
        }

        // 1) Max concurrency 2
        do {
            let lim = PhotoTransferLimiter(maximum: 2)
            let a = await lim.acquire(generation: 1)
            let b = await lim.acquire(generation: 1)
            check("max2_two_grants", a && b)
            async let third: Bool = lim.acquire(generation: 1)
            try? await Task.sleep(nanoseconds: 50_000_000)
            check("max2_third_waits", await lim.debugWaiting == 1, "waiting=\(await lim.debugWaiting)")
            await lim.release()
            let t = await third
            check("max2_third_handoff", t)
            await lim.release()
            await lim.release()
            check("max2_pool_full", await lim.debugAvailable == 2)
        }

        // 2) Direct handoff does not lose permits under queue pressure
        do {
            let lim = PhotoTransferLimiter(maximum: 2)
            _ = await lim.acquire(generation: 1)
            _ = await lim.acquire(generation: 1)
            async let w1 = lim.acquire(generation: 1)
            async let w2 = lim.acquire(generation: 1)
            try? await Task.sleep(nanoseconds: 30_000_000)
            check("handoff_two_waiting", await lim.debugWaiting == 2)
            await lim.release()
            await lim.release()
            let r1 = await w1
            let r2 = await w2
            check("handoff_both_true", r1 && r2)
            await lim.release()
            await lim.release()
            check("handoff_restored", await lim.debugAvailable == 2)
        }

        // 3) Generation-scoped cancel: gen1 waiters fail, gen2 can still acquire after release
        do {
            let lim = PhotoTransferLimiter(maximum: 1)
            _ = await lim.acquire(generation: 99)
            async let oldWait = lim.acquire(generation: 10)
            async let newWait = lim.acquire(generation: 11)
            try? await Task.sleep(nanoseconds: 30_000_000)
            await lim.cancelWaiters(generation: 10)
            let old = await oldWait
            check("cancel_old_false", old == false)
            await lim.release()
            let neu = await newWait
            check("cancel_new_granted", neu == true)
            await lim.release()
        }

        // 4) 50-item queue: only tokens, no payloads; all eventually complete
        do {
            let lim = PhotoTransferLimiter(maximum: 2)
            let n = 50
            await withTaskGroup(of: Bool.self) { group in
                for i in 0..<n {
                    group.addTask {
                        let ok = await lim.acquire(generation: 1)
                        if ok {
                            // Simulate short transfer without retaining bytes
                            try? await Task.sleep(nanoseconds: 1_000_000)
                            await lim.release()
                        }
                        return ok
                    }
                }
                var okCount = 0
                for await ok in group where ok { okCount += 1 }
                check("fifty_all_acquire", okCount == n, "ok=\(okCount)")
            }
            check("fifty_pool_restored", await lim.debugAvailable == 2)
        }

        // 5) Supersession: cancel gen waiters while holders of new gen proceed
        do {
            let lim = PhotoTransferLimiter(maximum: 2)
            _ = await lim.acquire(generation: 1)
            _ = await lim.acquire(generation: 1)
            async let stale = lim.acquire(generation: 1)
            try? await Task.sleep(nanoseconds: 20_000_000)
            await lim.cancelWaiters(generation: 1)
            check("supersede_stale_false", await stale == false)
            await lim.release()
            await lim.release()
            let fresh = await lim.acquire(generation: 2)
            check("supersede_fresh_true", fresh)
            await lim.release()
        }

        // 6) Retry overlap: a retry-shaped acquire waits under max=2 then handoffs while
        //    the original holders still finish (same shared limiter production uses).
        do {
            let lim = PhotoTransferLimiter(maximum: 2)
            _ = await lim.acquire(generation: 7)
            _ = await lim.acquire(generation: 7)
            async let retryA = lim.acquire(generation: 7) // "retry" of item while batch holds
            async let retryB = lim.acquire(generation: 7)
            try? await Task.sleep(nanoseconds: 30_000_000)
            check("retry_overlap_queued", await lim.debugWaiting == 2, "waiting=\(await lim.debugWaiting)")
            await lim.release() // one original finishes → handoff to first retry
            let rA = await retryA
            check("retry_overlap_handoff", rA)
            check("retry_overlap_one_still_waiting", await lim.debugWaiting == 1)
            await lim.release()
            let rB = await retryB
            check("retry_overlap_second", rB)
            await lim.release()
            await lim.release()
            check("retry_overlap_restored", await lim.debugAvailable == 2)
        }

        // 7) Individual task-handle pruning via production TransferTaskHandleRegistry
        do {
            var reg = TransferTaskHandleRegistry()
            let g: UInt64 = 42
            let a = UUID()
            let b = UUID()
            let c = UUID()
            reg.register(id: a, generation: g)
            reg.register(id: b, generation: g)
            reg.register(id: c, generation: g)
            check("registry_three", reg.count(generation: g) == 3)
            reg.prune(id: b, generation: g)
            check("registry_pruned_one", reg.count(generation: g) == 2)
            check("registry_still_has_a", reg.contains(id: a, generation: g))
            check("registry_no_b", !reg.contains(id: b, generation: g))
            reg.prune(id: a, generation: g)
            reg.prune(id: c, generation: g)
            check("registry_empty_after_all", reg.count(generation: g) == 0)
            check("registry_total_zero", reg.totalCount == 0)
            // Generation remove returns remaining ids (cancelGeneration path)
            reg.register(id: UUID(), generation: 9)
            reg.register(id: UUID(), generation: 9)
            let removed = reg.removeGeneration(9)
            check("registry_remove_gen", removed.count == 2 && reg.count(generation: 9) == 0)
        }

        print(failures == 0 ? "HARNESS_OK" : "HARNESS_FAIL count=\(failures)")
        if failures != 0 { exit(1) }
    }
}
