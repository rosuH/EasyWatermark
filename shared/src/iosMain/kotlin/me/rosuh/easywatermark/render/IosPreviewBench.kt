package me.rosuh.easywatermark.render

import platform.Foundation.NSLock
import platform.Foundation.NSLog
import kotlin.time.TimeSource

/**
 * Lightweight preview-pipeline timings for iOS filmstrip / editor work.
 *
 * Logs one structured line per completed scope so before/after optimizations can be compared in
 * Xcode / Console.app:
 *
 * ```
 * IosPreviewBench name=switch_preview totalMs=42 stages=read:8,downscale:12,raster:20,cachePut:1 hit=false path=…/ewm_src_…
 * ```
 *
 * Lines go to both `println` (Xcode console / test system-out) and `NSLog` (device unified log),
 * matching `IosDevicePerfBench` — a `println`-only line never reaches a `devicectl`-launched run.
 *
 * Not a formal JMH suite — production-safe logging for device/sim.
 */
/** J5: internal timing helper — not part of the Swift product API surface. */
internal object IosPreviewBench {
    private val timeSource = TimeSource.Monotonic

    fun scope(name: String): Scope = Scope(name, timeSource.markNow())

    private fun log(line: String) {
        println(line)
        NSLog("%s", line)
    }

    /**
     * Cross-thread stage accumulator.
     *
     * A host-level wall clock (filmstrip switch) runs its raster stages on `Dispatchers.Default`,
     * so the host cannot read them from its own [Scope]. While a window is open every [Scope.mark]
     * also adds its delta here, which lets a switch be decomposed into decode / compose / the
     * unattributed remainder without threading a bench object through the call graph.
     *
     * Accumulate-only: it never gates or changes raster behavior.
     */
    internal object Attribution {
        private val lock = NSLock()
        private val totals = LinkedHashMap<String, Long>()
        private var collecting = false

        /** Open a window and drop any previous totals. */
        fun begin() {
            lock.lock()
            try {
                totals.clear()
                collecting = true
            } finally {
                lock.unlock()
            }
        }

        /** Close the window and return the accumulated per-stage totals. */
        fun end(): Map<String, Long> {
            lock.lock()
            return try {
                collecting = false
                LinkedHashMap(totals)
            } finally {
                lock.unlock()
            }
        }

        fun snapshot(): Map<String, Long> {
            lock.lock()
            return try {
                LinkedHashMap(totals)
            } finally {
                lock.unlock()
            }
        }

        internal fun add(stage: String, ms: Long) {
            lock.lock()
            try {
                if (!collecting) return
                totals[stage] = (totals[stage] ?: 0L) + ms
            } finally {
                lock.unlock()
            }
        }

        /** ImageIO source decode inside the window (placeholder + non-reused watermarked). */
        fun decodeMs(stages: Map<String, Long>): Long = stages[STAGE_IMAGE_IO].orZero()

        /** Watermark cell compose + tiling inside the window. */
        fun composeMs(stages: Map<String, Long>): Long = stages[STAGE_COMPOSE].orZero()

        /** Watermark icon read + decode inside the window (Image mode only). */
        fun iconMs(stages: Map<String, Long>): Long = stages[STAGE_ICON].orZero()

        private fun Long?.orZero(): Long = this ?: 0L
    }

    /** ImageIO thumbnail decode of the photo source. */
    const val STAGE_IMAGE_IO: String = "imageIOThumbnail"

    /** [CommonWatermarkPipeline] compose over the decoded source. */
    const val STAGE_COMPOSE: String = "compose"

    /** Watermark icon file read + decode (Image mode). */
    const val STAGE_ICON: String = "iconDecode"

    class Scope internal constructor(
        private val name: String,
        private val start: TimeSource.Monotonic.ValueTimeMark,
    ) {
        private val marks = ArrayList<Pair<String, Long>>(8)
        private var last = start

        fun mark(stage: String) {
            val now = timeSource.markNow()
            val ms = (now - last).inWholeMilliseconds
            marks += stage to ms
            last = now
            Attribution.add(stage, ms)
        }

        fun finish(
            extra: Map<String, Any?> = emptyMap(),
        ) {
            val totalMs = (timeSource.markNow() - start).inWholeMilliseconds
            val stages = marks.joinToString(",") { (k, v) -> "$k:${v}" }
            val extras = extra.entries.joinToString(" ") { (k, v) -> "$k=$v" }
            log(
                "IosPreviewBench name=$name totalMs=$totalMs stages=$stages" +
                    if (extras.isNotEmpty()) " $extras" else "",
            )
        }
    }
}
