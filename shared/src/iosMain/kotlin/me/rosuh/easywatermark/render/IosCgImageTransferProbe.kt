package me.rosuh.easywatermark.render

import kotlin.concurrent.Volatile
import platform.Foundation.NSLock

/**
 * CGImage → Skia hand-off mode for Phase-1 A/B.
 *
 * Production default is [SkiaOwned]. [LegacyByteArray] exists only so benches can compare the
 * pre-Phase-1 stack (Kotlin `ByteArray` + skiko memcpy + optional Compose re-raster) without
 * checking out an old commit.
 */
internal enum class IosCgImageTransferMode {
    /** Pre-Phase-1: pin a Kotlin [ByteArray], draw, then `installPixels` / `makeRaster(ByteArray)`. */
    LegacyByteArray,

    /** Phase-1: draw into Skia-owned memory (`allocPixels` / `Data.makeUninitialized`). */
    SkiaOwned,
}

/**
 * Last-transfer accounting for A/B benches. Not a production telemetry channel.
 *
 * - [frameBytes]: `w × h × 4` of the CGImage we drew into.
 * - [accountedAllocBytes]: sum of full-frame buffers allocated on the transfer path
 *   (research §1.2 "wasted writes" model — not OS RSS).
 * - [fullFrameWrites]: how many times those bytes were written end-to-end for this surface.
 * - [drawNs] / [handoffNs]: `CGContextDrawImage` vs everything after until the Skia/Compose object
 *   is published.
 */
internal data class IosCgImageTransferSample(
    val mode: IosCgImageTransferMode,
    val surface: String,
    val width: Int,
    val height: Int,
    val frameBytes: Int,
    val accountedAllocBytes: Int,
    val fullFrameWrites: Int,
    val drawNs: Long,
    val handoffNs: Long,
) {
    val transferNs: Long get() = drawNs + handoffNs
}

internal object IosCgImageTransferProbe {
    private val lock = NSLock()

    @Volatile
    var mode: IosCgImageTransferMode = IosCgImageTransferMode.SkiaOwned

    @Volatile
    private var last: IosCgImageTransferSample? = null

    fun resetForTests() {
        lock.lock()
        try {
            last = null
            mode = IosCgImageTransferMode.SkiaOwned
        } finally {
            lock.unlock()
        }
    }

    fun lastOrNull(): IosCgImageTransferSample? {
        lock.lock()
        return try {
            last
        } finally {
            lock.unlock()
        }
    }

    fun record(sample: IosCgImageTransferSample) {
        lock.lock()
        try {
            last = sample
        } finally {
            lock.unlock()
        }
    }

    inline fun <T> withMode(mode: IosCgImageTransferMode, block: () -> T): T {
        val previous = this.mode
        this.mode = mode
        return try {
            block()
        } finally {
            this.mode = previous
        }
    }
}
