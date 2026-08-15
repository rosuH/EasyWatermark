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
 * Optional Library Read (ADR-0029).
 *
 * Production requests once on first real PhotoKit need via [requestOnceIfNeeded].
 * Allow All is not required to pick, edit, or export. After a non-Allow-All
 * result the editor may show a Library Read upsell (P5 / Q11=B).
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
    private var statusOverrideForTests: Status? = null

    fun status(): Status {
        lock.lock()
        val override = statusOverrideForTests
        lock.unlock()
        if (override != null) return override
        return fromNative(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite))
    }

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
        statusOverrideForTests = null
        lock.unlock()
    }

    internal fun installStatusForTests(status: Status?) {
        lock.lock()
        statusOverrideForTests = status
        lock.unlock()
    }

    internal fun markRequestedForTests() {
        lock.lock()
        requestedThisProcess = true
        lock.unlock()
    }

    internal fun hasRequestedThisProcess(): Boolean {
        lock.lock()
        return try {
            requestedThisProcess
        } finally {
            lock.unlock()
        }
    }

    internal fun requestedThisProcessForTests(): Boolean = hasRequestedThisProcess()

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
