@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSLock
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * Optional Library Read status (ADR-0029 P2).
 *
 * Production editor must not call [requestOnceIfNeeded]. Only the
 * `-ewmPhotoKitFastPath` bench arm may request.
 */
internal object IosPhotoLibraryAccess {
    enum class Status {
        NotDetermined,
        Restricted,
        Denied,
        Authorized,
        Limited,
    }

    private val lock = NSLock()
    private var requestedThisProcess = false

    fun status(): Status =
        fromNative(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite))

    /** Allow All only. Limited is first-class miss, not usable. */
    fun isUsable(): Boolean = status() == Status.Authorized

    /**
     * One ReadWrite prompt per process. Does not treat Limited/Denied as a
     * retryable error.
     */
    suspend fun requestOnceIfNeeded(): Status {
        lock.lock()
        val already = requestedThisProcess
        requestedThisProcess = true
        lock.unlock()
        val current = status()
        if (already || current != Status.NotDetermined) return current
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { raw ->
                if (cont.isActive) cont.resume(fromNative(raw))
            }
        }
    }

    internal fun resetForTests() {
        lock.lock()
        requestedThisProcess = false
        lock.unlock()
    }

    internal fun markRequestedForTests() {
        lock.lock()
        requestedThisProcess = true
        lock.unlock()
    }

    internal fun requestedThisProcessForTests(): Boolean {
        lock.lock()
        return try {
            requestedThisProcess
        } finally {
            lock.unlock()
        }
    }

    private fun fromNative(raw: Long): Status = when (raw) {
        PHAuthorizationStatusAuthorized -> Status.Authorized
        PHAuthorizationStatusLimited -> Status.Limited
        PHAuthorizationStatusDenied -> Status.Denied
        PHAuthorizationStatusRestricted -> Status.Restricted
        PHAuthorizationStatusNotDetermined -> Status.NotDetermined
        else -> Status.NotDetermined
    }

    fun logLabel(status: Status = status()): String = when (status) {
        Status.Authorized -> "authorized"
        Status.Limited -> "limited"
        Status.Denied -> "denied"
        Status.Restricted -> "restricted"
        Status.NotDetermined -> "notDetermined"
    }
}
