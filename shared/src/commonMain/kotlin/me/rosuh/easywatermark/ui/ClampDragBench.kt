package me.rosuh.easywatermark.ui

import kotlin.time.TimeSource

/**
 * H0.1 / H0.1-fix measurement timings for CLAMP preview drag → Session commit → host preview.
 *
 * One structured println per completed gesture (and optional host preview scopes), e.g.:
 * ```
 * ClampDragBench name=gesture … sampleCount=12 committed=true liveDraft=true
 * ClampDragBench name=desktop_offset_preview totalMs=12 stages=read:1,compose:11 path=light
 * ```
 *
 * **Not** an SLO and **not** a product state owner. Gated by [enabled].
 */
object ClampDragBench {
    /**
     * When false, mark/finish become no-ops (except [lastLine] stays from the last enabled run).
     * Production leaves true; unit tests may set false to silence noise.
     */
    var enabled: Boolean = true

    /** Last finished log line (test seam). */
    var lastLine: String? = null
        private set

    /** Last finished gesture commit count for the completed scope (0 or 1). Test seam. */
    var lastCommitCount: Int = 0
        private set

    /** Last finished sample count (drag move events while active). Test seam. */
    var lastSampleCount: Int = 0
        private set

    /** Last finished gesture reported live UI draft emissions. Test seam. */
    var lastLiveDraft: Boolean = false
        private set

    private val timeSource = TimeSource.Monotonic

    fun resetForTests() {
        lastLine = null
        lastCommitCount = 0
        lastSampleCount = 0
        lastLiveDraft = false
    }

    fun gestureScope(): GestureScope = GestureScope(timeSource.markNow())

    fun previewScope(name: String): PreviewScope = PreviewScope(name, timeSource.markNow())

    class GestureScope internal constructor(
        private val start: TimeSource.Monotonic.ValueTimeMark,
    ) {
        private val marks = ArrayList<Pair<String, Long>>(8)
        private var last = start
        private var sampleCount = 0
        private var committed = false
        private var liveDraft = false
        private var active = true

        fun mark(stage: String) {
            if (!enabled || !active) return
            val now = timeSource.markNow()
            val ms = (now - last).inWholeMilliseconds
            marks += stage to ms
            last = now
        }

        /** Coalesced drag sample (count only; stage time rolls into next mark). */
        fun sample() {
            if (!enabled || !active) return
            sampleCount++
        }

        /** H0.1-fix: UI-only draft offset was emitted for live preview paint. */
        fun noteLiveDraft() {
            if (!enabled || !active) return
            liveDraft = true
        }

        /**
         * Call **after** host [onOffsetCommit] returns so the stage includes applyOffset +
         * host invalidation kickoff (not mid-gesture raster — that is a separate preview scope).
         */
        fun markCommitDone() {
            if (!enabled || !active) return
            committed = true
            mark("onOffsetCommit")
        }

        fun finish(extra: Map<String, Any?> = emptyMap()) {
            if (!enabled || !active) return
            active = false
            val totalMs = (timeSource.markNow() - start).inWholeMilliseconds
            val stages = marks.joinToString(",") { (k, v) -> "$k:$v" }
            val baseExtras = linkedMapOf<String, Any?>(
                "sampleCount" to sampleCount,
                "committed" to committed,
                "liveDraft" to liveDraft,
            )
            baseExtras.putAll(extra)
            val extras = baseExtras.entries.joinToString(" ") { (k, v) -> "$k=$v" }
            val line =
                "ClampDragBench name=gesture totalMs=$totalMs stages=$stages $extras"
            lastLine = line
            lastCommitCount = if (committed) 1 else 0
            lastSampleCount = sampleCount
            lastLiveDraft = liveDraft
            println(line)
        }
    }

    class PreviewScope internal constructor(
        private val name: String,
        private val start: TimeSource.Monotonic.ValueTimeMark,
    ) {
        private val marks = ArrayList<Pair<String, Long>>(8)
        private var last = start
        private var active = true

        fun mark(stage: String) {
            if (!enabled || !active) return
            val now = timeSource.markNow()
            val ms = (now - last).inWholeMilliseconds
            marks += stage to ms
            last = now
        }

        fun finish(extra: Map<String, Any?> = emptyMap()) {
            if (!enabled || !active) return
            active = false
            val totalMs = (timeSource.markNow() - start).inWholeMilliseconds
            val stages = marks.joinToString(",") { (k, v) -> "$k:$v" }
            val extras = extra.entries.joinToString(" ") { (k, v) -> "$k=$v" }
            val line =
                "ClampDragBench name=$name totalMs=$totalMs stages=$stages" +
                    if (extras.isNotEmpty()) " $extras" else ""
            lastLine = line
            println(line)
        }
    }
}
