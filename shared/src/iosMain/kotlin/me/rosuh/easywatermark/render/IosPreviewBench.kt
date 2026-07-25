package me.rosuh.easywatermark.render

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
 * Not a formal JMH suite — production-safe NSLog-style println for device/sim.
 */
/** J5: internal timing helper — not part of the Swift product API surface. */
internal object IosPreviewBench {
    private val timeSource = TimeSource.Monotonic

    fun scope(name: String): Scope = Scope(name, timeSource.markNow())

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
        }

        fun finish(
            extra: Map<String, Any?> = emptyMap(),
        ) {
            val totalMs = (timeSource.markNow() - start).inWholeMilliseconds
            val stages = marks.joinToString(",") { (k, v) -> "$k:${v}" }
            val extras = extra.entries.joinToString(" ") { (k, v) -> "$k=$v" }
            println(
                "IosPreviewBench name=$name totalMs=$totalMs stages=$stages" +
                    if (extras.isNotEmpty()) " $extras" else "",
            )
        }
    }
}
