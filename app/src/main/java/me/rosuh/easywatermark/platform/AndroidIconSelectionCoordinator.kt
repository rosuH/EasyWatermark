package me.rosuh.easywatermark.platform

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.MediaRef

/** Orders durable icon copy, persisted config commit, and bounded old-file cleanup. */
internal class AndroidIconSelectionCoordinator(
    private val store: DurableIconStore,
    private val currentIcon: suspend () -> MediaRef,
    private val commitIcon: suspend (MediaRef) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun import(source: Uri): Result<MediaRef> = mutex.withLock {
        val previous = try {
            currentIcon()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return@withLock Result.failure(failure)
        }
        runCatching { store.pruneExcept(previous) }
        val copied = store.copyToOwnedRef(source)
        val next = copied.getOrElse { return@withLock copied }

        var committed = false
        try {
            // Once bytes are published, finish config + cleanup as one non-cancellable ordering
            // window. A newer selection waits on [mutex] and then deterministically wins.
            withContext(NonCancellable) {
                commitIcon(next)
                committed = true
                runCatching { store.deleteIfOwned(previous) }
                runCatching { store.pruneExcept(next) }
            }
            Result.success(next)
        } catch (cancelled: CancellationException) {
            if (!committed) store.deleteIfOwned(next)
            throw cancelled
        } catch (failure: Throwable) {
            if (!committed) store.deleteIfOwned(next)
            Result.failure(failure)
        }
    }
}
