package me.rosuh.easywatermark.ui.image

/**
 * Filmstrip [ProductAsyncImage] can land on Error when LazyRow disposes an in-flight
 * 4K decode (import layout / snap). Sequential Coil of the same file succeeds — retry.
 */
object ProductThumbLoadPolicy {
    const val MAX_RETRIES: Int = 2

    fun shouldRetry(attempt: Int): Boolean = attempt in 0 until MAX_RETRIES
}
