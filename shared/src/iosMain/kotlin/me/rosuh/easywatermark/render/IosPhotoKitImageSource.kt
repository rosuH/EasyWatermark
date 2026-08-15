@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import kotlinx.cinterop.cValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Bitmap as SkiaBitmap
import platform.CoreGraphics.CGSize
import platform.Foundation.NSNumber
import platform.Photos.PHAsset
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeOpportunistic
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHImageResultIsDegradedKey
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume
import kotlin.time.TimeSource

/** One PhotoKit frame. Never written to SourcePlaceholder / Watermarked caches (P2). */
internal data class PhotoKitFrame(
    val bitmap: SkiaBitmap,
    val degraded: Boolean,
)

internal data class PhotoKitRequestTiming(
    val frame: PhotoKitFrame? = null,
    val degradedMs: Long? = null,
    val finalMs: Long? = null,
    val timedOut: Boolean = false,
    val missed: Boolean = false,
)

/**
 * Library-derivative producer (ADR-0029 P2). Offline only.
 *
 * Production editor does not call this. Bench (`-ewmPhotoKitFastPath`) may.
 */
internal object IosPhotoKitImageSource {
    fun resolveAsset(localIdentifier: String): PHAsset? {
        if (localIdentifier.isBlank()) return null
        val result = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
        if (result.count() == 0uL) return null
        return result.firstObject() as? PHAsset
    }

    suspend fun requestBitmap(
        asset: PHAsset,
        targetPx: Int,
        deadlineMs: Long,
    ): PhotoKitFrame? {
        val timing = requestTimed(asset, targetPx, deadlineMs)
        return timing.frame.takeIf { !timing.timedOut && !timing.missed }
    }

    suspend fun requestTimed(
        asset: PHAsset,
        targetPx: Int,
        deadlineMs: Long,
    ): PhotoKitRequestTiming {
        if (deadlineMs <= 0L) {
            return PhotoKitRequestTiming(timedOut = true)
        }
        val options = PHImageRequestOptions()
        options.deliveryMode = PHImageRequestOptionsDeliveryModeOpportunistic
        options.resizeMode = PHImageRequestOptionsResizeModeFast
        options.networkAccessAllowed = false
        options.setSynchronous(false)
        val targetSize = cValue<CGSize> {
            width = targetPx.toDouble()
            height = targetPx.toDouble()
        }
        val manager = PHImageManager.defaultManager()
        return awaitImageRequest(deadlineMs) { onFrame, onMiss, onCancelable ->
            val requestId = manager.requestImageForAsset(
                asset = asset,
                targetSize = targetSize,
                contentMode = PHImageContentModeAspectFit,
                options = options,
            ) { image, info ->
                val cgImage = image?.CGImage
                if (cgImage == null) {
                    onMiss()
                    return@requestImageForAsset
                }
                val degraded = infoIsDegraded(info)
                val bitmap = IosCgImageBridge.toSkiaBitmap(cgImage)
                onFrame(PhotoKitFrame(bitmap = bitmap, degraded = degraded))
            }
            onCancelable(requestId) { id -> manager.cancelImageRequest(id) }
        }
    }

    /**
     * Timeout wrapper shared with tests. [start] must call [onCancelable] with the
     * native request id and a cancel function.
     */
    internal suspend fun awaitImageRequest(
        deadlineMs: Long,
        start: (
            onFrame: (PhotoKitFrame) -> Unit,
            onMiss: () -> Unit,
            onCancelable: (requestId: Int, cancel: (Int) -> Unit) -> Unit,
        ) -> Unit,
    ): PhotoKitRequestTiming = coroutineScope {
        if (deadlineMs <= 0L) return@coroutineScope PhotoKitRequestTiming(timedOut = true)
        val mark = TimeSource.Monotonic.markNow()
        suspendCancellableCoroutine { cont ->
            val done = AtomicInt(0)
            val requestId = AtomicInt(0)
            var cancelFn: ((Int) -> Unit)? = null
            var lastDegradedMs: Long? = null
            fun complete(value: PhotoKitRequestTiming) {
                if (!done.compareAndSet(0, 1)) return
                if (cont.isActive) cont.resume(value)
            }
            val timeoutJob = launch {
                delay(deadlineMs)
                cancelFn?.invoke(requestId.value)
                complete(
                    PhotoKitRequestTiming(
                        timedOut = true,
                        degradedMs = lastDegradedMs,
                    ),
                )
            }
            start(
                { frame ->
                    val elapsed = mark.elapsedNow().inWholeMilliseconds
                    if (frame.degraded) {
                        lastDegradedMs = lastDegradedMs ?: elapsed
                    } else {
                        timeoutJob.cancel()
                        complete(
                            PhotoKitRequestTiming(
                                frame = frame,
                                degradedMs = lastDegradedMs,
                                finalMs = elapsed,
                            ),
                        )
                    }
                },
                {
                    timeoutJob.cancel()
                    complete(PhotoKitRequestTiming(missed = true, degradedMs = lastDegradedMs))
                },
                { id, cancel ->
                    requestId.value = id
                    cancelFn = cancel
                },
            )
            cont.invokeOnCancellation {
                timeoutJob.cancel()
                cancelFn?.invoke(requestId.value)
            }
        }
    }

    private fun infoIsDegraded(info: Map<Any?, *>?): Boolean {
        val raw = info?.get(PHImageResultIsDegradedKey) ?: return false
        return when (raw) {
            is Boolean -> raw
            is NSNumber -> raw.boolValue
            else -> raw.toString() == "1" || raw.toString().equals("true", ignoreCase = true)
        }
    }
}
