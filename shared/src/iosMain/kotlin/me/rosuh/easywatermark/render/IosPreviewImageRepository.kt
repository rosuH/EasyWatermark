package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Decode identity: same path + bucket + purpose always shares one cold request. */
internal data class IosPreviewKey(
    val ownedPath: String,
    val pixelBucket: Int,
    val purpose: IosPreviewPurpose,
)

internal enum class IosPreviewPurpose {
    SourcePlaceholder,
    Watermarked,
    Filmstrip,
    ExportThumbnail,
}

internal data class IosPreviewRepositorySnapshot(
    val cachedEntries: Int,
    val inFlightEntries: Int,
    val previewBytes: Long,
    val filmstripBytes: Long,
    val closed: Boolean,
    val watermarkedEntries: Int = 0,
    val sourcePlaceholderEntries: Int = 0,
    val filmstripEntries: Int = 0,
    val exportThumbnailEntries: Int = 0,
    val watermarkedBytes: Long = 0,
    val sourcePlaceholderBytes: Long = 0,
    val exportThumbnailBytes: Long = 0,
    val cachedKeys: Set<IosPreviewKey> = emptySet(),
)

/**
 * One host-owned source/preview cache state machine.
 *
 * Every cache/in-flight/epoch/closed read or write is guarded by [mutex]. The owner completion
 * lives under [ownerScope]'s lifecycle, while individual waiters are allowed to cancel normally:
 * cancelling one visible cell never strands an in-flight map entry or forces another waiter to
 * wait in NonCancellable. `clear` and `close` both gate late completions before caching them.
 */
internal class IosPreviewImageRepository(
    private val ownerScope: CoroutineScope,
    private var sourceAndPreviewBytesMax: Long = SOURCE_AND_PREVIEW_BYTES_MAX,
    private val filmstripBytesMax: Long = FILMSTRIP_BYTES_MAX,
    private val watermarkedEntriesMax: Int = DEFAULT_WATERMARKED_ENTRIES_MAX,
    private val sourcePlaceholderEntriesMax: Int = DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX,
    private val filmstripEntriesMax: Int = DEFAULT_FILMSTRIP_ENTRIES_MAX,
    private val exportThumbnailEntriesMax: Int = DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX,
    private var watermarkedBytesMax: Long = DEFAULT_WATERMARKED_BYTES_MAX,
    private var sourcePlaceholderBytesMax: Long = DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX,
    private val exportThumbnailBytesMax: Long = DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX,
) {
    private data class InFlight(
        val epoch: Long,
        val deferred: CompletableDeferred<ImageBitmap?>,
    )

    private val mutex = Mutex()
    private val cache = linkedMapOf<IosPreviewKey, ImageBitmap>()
    private val inFlight = mutableMapOf<IosPreviewKey, InFlight>()
    private var epoch = 0L
    private var closed = false

    // Completions share the owner dispatcher; Job is a supervisor so one decode failure does not
    // cancel siblings. Permanent close marks [closed] synchronously in [closeFromOwner] *before*
    // Host cancelChildren so a cancelled child cannot leave the repository open.
    private val completionJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val completionScope = CoroutineScope(ownerScope.coroutineContext + completionJob)
    /** Contended close only — never a child of the Host Job. */
    private val orphanCloseJob = SupervisorJob()

    suspend fun load(
        key: IosPreviewKey,
        decoder: suspend () -> ImageBitmap?,
    ): ImageBitmap? {
        require(key.ownedPath.isNotBlank()) { "IosPreviewImageRepository: blank owned path" }
        require(key.pixelBucket > 0) { "IosPreviewImageRepository: non-positive bucket" }
        val deferred: Deferred<ImageBitmap?> = mutex.withLock {
            if (closed) return@withLock completedDeferred(null)
            cache[key]?.let { return@withLock completedDeferred(it) }
            inFlight[key]?.deferred ?: startCompletionLocked(key, decoder)
        }
        // Deliberately no NonCancellable wrapper: caller cancellation is normal UI lifecycle.
        return deferred.await()
    }

    /** Cache-only lookup; unlike [load], this never begins a decode. */
    suspend fun cached(key: IosPreviewKey): ImageBitmap? = mutex.withLock {
        if (closed) null else cache[key]
    }

    /**
     * Non-suspending cache peek for composition hot paths (filmstrip scroll).
     * Uncontended: returns the cached bitmap. Contended/closed: null (caller falls through to load).
     * Never starts a decode and never blocks the main thread on the repository mutex.
     */
    fun peekCached(key: IosPreviewKey): ImageBitmap? {
        if (closed) return null
        if (!mutex.tryLock()) return null
        try {
            if (closed) return null
            return cache[key]
        } finally {
            mutex.unlock()
        }
    }

    suspend fun invalidate(key: IosPreviewKey) {
        mutex.withLock {
            cache.remove(key)
            inFlight.remove(key)?.deferred?.cancel()
        }
    }

    /** Remove every pixel bucket for one source/purpose (for example after an offset commit). */
    suspend fun invalidateOwnedPath(ownedPath: String, purpose: IosPreviewPurpose) {
        mutex.withLock {
            cache.keys
                .filter { it.ownedPath == ownedPath && it.purpose == purpose }
                .toList()
                .forEach(cache::remove)
            inFlight.keys
                .filter { it.ownedPath == ownedPath && it.purpose == purpose }
                .toList()
                .forEach { key -> inFlight.remove(key)?.deferred?.cancel() }
        }
    }

    /** Invalidate all completed values; a late pre-clear completion cannot repopulate them. */
    suspend fun clear() {
        mutex.withLock {
            epoch += 1
            cache.clear()
            inFlight.values.forEach { it.deferred.cancel() }
            inFlight.clear()
        }
    }

    /** Invalidate only one product use case (for example, config-invalidated watermarked previews). */
    suspend fun clearPurpose(purpose: IosPreviewPurpose) {
        mutex.withLock {
            cache.keys
                .filter { it.purpose == purpose }
                .toList()
                .forEach(cache::remove)
            inFlight.keys
                .filter { it.purpose == purpose }
                .toList()
                .forEach { key -> inFlight.remove(key)?.deferred?.cancel() }
        }
    }

    /** Permanent Host teardown. Subsequent requests fail closed and owner work is cancelled. */
    suspend fun close() {
        val waiters = mutex.withLock { markClosedAndDrainLocked() }
        // Complete *outside* the mutex: Main.immediate / Unconfined waiters resume synchronously
        // and may re-enter [load]/[cached]/[snapshot], which also take [mutex].
        waiters.forEach { it.complete(null) }
        completionJob.cancel()
        orphanCloseJob.cancel()
    }

    /**
     * Owner lifecycle bridge for non-suspending UIKit/Compose disposal hooks.
     *
     * When the mutex is free, [closed]/cache/inFlight are updated **synchronously** before this
     * returns so Host [cancelChildren] cannot abort a still-open repository. Deferred completion
     * always happens **after** unlock. When contended, close runs on [orphanCloseJob] (not a Host
     * child) so cancelChildren cannot kill it.
     */
    fun closeFromOwner() {
        if (mutex.tryLock()) {
            val waiters = try {
                markClosedAndDrainLocked()
            } finally {
                mutex.unlock()
            }
            waiters.forEach { it.complete(null) }
            return
        }
        CoroutineScope(ownerScope.coroutineContext.minusKey(Job) + orphanCloseJob)
            .launch(start = CoroutineStart.UNDISPATCHED) {
                close()
            }
    }

    /**
     * Under [mutex]: mark closed, clear cache/inFlight, return deferreds to complete **outside**
     * the lock. Completing under the lock re-enters [load] on Unconfined/Main.immediate.
     */
    private fun markClosedAndDrainLocked(): List<CompletableDeferred<ImageBitmap?>> {
        if (closed) return emptyList()
        closed = true
        epoch += 1
        cache.clear()
        val pending = inFlight.values.map { it.deferred }
        inFlight.clear()
        return pending
    }

    /**
     * Test seam: hold the repository mutex while [block] runs so [closeFromOwner] must take the
     * contended orphan path. Production code never calls this.
     */
    internal suspend fun withMutexHeldForTests(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }

    fun clearFromOwner() {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) { clear() }
    }

    fun clearPurposeFromOwner(purpose: IosPreviewPurpose) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) { clearPurpose(purpose) }
    }

    fun invalidateFromOwner(key: IosPreviewKey) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) { invalidate(key) }
    }

    /**
     * Apply formula caps for the current preview long-edge. Sync when the mutex is free so
     * Host layout / bench pin takes effect before the next put.
     */
    fun applyWorkingSetCapsFromOwner(caps: PreviewWorkingSetCaps) {
        if (mutex.tryLock()) {
            try {
                applyWorkingSetCapsLocked(caps)
            } finally {
                mutex.unlock()
            }
            return
        }
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { applyWorkingSetCapsLocked(caps) }
        }
    }

    suspend fun applyWorkingSetCaps(caps: PreviewWorkingSetCaps) {
        mutex.withLock { applyWorkingSetCapsLocked(caps) }
    }

    private fun applyWorkingSetCapsLocked(caps: PreviewWorkingSetCaps) {
        sourcePlaceholderBytesMax = caps.sourceBytesMax
        watermarkedBytesMax = caps.watermarkedBytesMax
        sourceAndPreviewBytesMax = caps.jointBytesMax
        if (!closed) {
            enforceBudgetsLocked()
        }
    }

    fun invalidateOwnedPathFromOwner(ownedPath: String, purpose: IosPreviewPurpose) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            invalidateOwnedPath(ownedPath, purpose)
        }
    }

    /** Test/host cache insertion for values already decoded by a renderer edge. */
    suspend fun putForTests(key: IosPreviewKey, bitmap: ImageBitmap) {
        mutex.withLock {
            if (closed) return
            cache[key] = bitmap
            enforceBudgetsLocked()
        }
    }

    /** Synchronous test seam only; it never waits for a mutex or performs IO. */
    internal fun putForTestsImmediate(key: IosPreviewKey, bitmap: ImageBitmap) {
        check(mutex.tryLock()) { "IosPreviewImageRepository test seam contended" }
        try {
            if (closed) return
            cache[key] = bitmap
            enforceBudgetsLocked()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun snapshot(): IosPreviewRepositorySnapshot = mutex.withLock {
        snapshotLocked()
    }

    /**
     * Synchronous test seam only; production reads through suspend APIs.
     * When the owner completion holds the mutex, return an empty-cache snapshot rather than
     * throwing — Host path identity (`previewSourcePath`) remains authoritative.
     */
    internal fun snapshotForTestsImmediate(): IosPreviewRepositorySnapshot {
        if (!mutex.tryLock()) {
            return IosPreviewRepositorySnapshot(
                cachedEntries = 0,
                inFlightEntries = -1,
                previewBytes = 0,
                filmstripBytes = 0,
                closed = closed,
                cachedKeys = emptySet(),
            )
        }
        return try {
            snapshotLocked()
        } finally {
            mutex.unlock()
        }
    }

    private fun startCompletionLocked(
        key: IosPreviewKey,
        decoder: suspend () -> ImageBitmap?,
    ): CompletableDeferred<ImageBitmap?> {
        val deferred = CompletableDeferred<ImageBitmap?>()
        val entry = InFlight(epoch = epoch, deferred = deferred)
        inFlight[key] = entry
        completionScope.launch {
            val decoded = runCatching { decoder() }.getOrNull()
            // The completion cleanup must run even when the owning Host cancels its scope. It does
            // not publish pixels after close/clear; it only resolves the map/deferred lifecycle.
            //
            // Complete the deferred *outside* the mutex: waiters resume synchronously under
            // Unconfined/Main.immediate and may re-enter [load]/[cached], which also takes [mutex].
            // Completing while holding the lock surfaces as CompletionHandlerException.
            val toComplete: ImageBitmap? = withContext(NonCancellable) {
                mutex.withLock {
                    val current = inFlight[key] === entry
                    if (current) {
                        inFlight.remove(key)
                    }
                    val cacheable = current && !closed && entry.epoch == epoch && decoded != null
                    if (cacheable) {
                        cache[key] = decoded
                        enforceBudgetsLocked()
                    }
                    if (cacheable || current) decoded else null
                }
            }
            deferred.complete(toComplete)
        }
        return deferred
    }

    private fun completedDeferred(value: ImageBitmap?): CompletableDeferred<ImageBitmap?> =
        CompletableDeferred<ImageBitmap?>().also { it.complete(value) }

    private fun enforceBudgetsLocked() {
        evictToEntryCapLocked(IosPreviewPurpose.Watermarked, watermarkedEntriesMax)
        evictToEntryCapLocked(IosPreviewPurpose.SourcePlaceholder, sourcePlaceholderEntriesMax)
        evictToEntryCapLocked(IosPreviewPurpose.Filmstrip, filmstripEntriesMax)
        evictToEntryCapLocked(IosPreviewPurpose.ExportThumbnail, exportThumbnailEntriesMax)
        evictOldestMatchingLocked(
            maxBytes = watermarkedBytesMax,
            matches = { it.purpose == IosPreviewPurpose.Watermarked },
        )
        evictOldestMatchingLocked(
            maxBytes = sourcePlaceholderBytesMax,
            matches = { it.purpose == IosPreviewPurpose.SourcePlaceholder },
        )
        evictOldestMatchingLocked(
            maxBytes = exportThumbnailBytesMax,
            matches = { it.purpose == IosPreviewPurpose.ExportThumbnail },
        )
        // Joint non-filmstrip budget: total Source+Watermarked+Export bytes vs one cap.
        // Prefer dropping Export → Source before Watermarked so export-sheet thumbs cannot
        // blank the editor preview (bytes check is joint, not per-purpose).
        evictJointNonFilmstripLocked(sourceAndPreviewBytesMax)
        evictOldestMatchingLocked(
            maxBytes = filmstripBytesMax,
            matches = { it.purpose == IosPreviewPurpose.Filmstrip },
        )
    }

    private fun evictToEntryCapLocked(purpose: IosPreviewPurpose, maxEntries: Int) {
        while (cache.keys.count { it.purpose == purpose } > maxEntries) {
            val oldest = cache.keys.firstOrNull { it.purpose == purpose } ?: return
            cache.remove(oldest)
        }
    }

    private fun evictOldestMatchingLocked(
        maxBytes: Long,
        matches: (IosPreviewKey) -> Boolean,
    ) {
        while (bytesForLocked(matches) > maxBytes) {
            val oldest = cache.keys.firstOrNull(matches) ?: return
            cache.remove(oldest)
        }
    }

    /**
     * While joint non-filmstrip bytes exceed [maxBytes], remove the oldest entry in priority
     * order ExportThumbnail → SourcePlaceholder → Watermarked (never Filmstrip here).
     */
    private fun evictJointNonFilmstripLocked(maxBytes: Long) {
        fun isNonFilmstrip(key: IosPreviewKey) =
            key.purpose != IosPreviewPurpose.Filmstrip
        while (bytesForLocked(::isNonFilmstrip) > maxBytes) {
            val oldest = cache.keys.firstOrNull {
                it.purpose == IosPreviewPurpose.ExportThumbnail
            } ?: cache.keys.firstOrNull {
                it.purpose == IosPreviewPurpose.SourcePlaceholder
            } ?: cache.keys.firstOrNull {
                it.purpose == IosPreviewPurpose.Watermarked
            } ?: return
            cache.remove(oldest)
        }
    }

    private fun bytesForLocked(matches: (IosPreviewKey) -> Boolean): Long =
        cache.entries
            .asSequence()
            .filter { matches(it.key) }
            .sumOf { (_, bitmap) -> approxBytes(bitmap) }

    private fun snapshotLocked(): IosPreviewRepositorySnapshot =
        IosPreviewRepositorySnapshot(
            cachedEntries = cache.size,
            inFlightEntries = inFlight.size,
            previewBytes = bytesForLocked { it.purpose != IosPreviewPurpose.Filmstrip },
            filmstripBytes = bytesForLocked { it.purpose == IosPreviewPurpose.Filmstrip },
            closed = closed,
            watermarkedEntries = cache.keys.count { it.purpose == IosPreviewPurpose.Watermarked },
            sourcePlaceholderEntries = cache.keys.count {
                it.purpose == IosPreviewPurpose.SourcePlaceholder
            },
            filmstripEntries = cache.keys.count { it.purpose == IosPreviewPurpose.Filmstrip },
            exportThumbnailEntries = cache.keys.count {
                it.purpose == IosPreviewPurpose.ExportThumbnail
            },
            watermarkedBytes = bytesForLocked { it.purpose == IosPreviewPurpose.Watermarked },
            sourcePlaceholderBytes = bytesForLocked {
                it.purpose == IosPreviewPurpose.SourcePlaceholder
            },
            exportThumbnailBytes = bytesForLocked {
                it.purpose == IosPreviewPurpose.ExportThumbnail
            },
            cachedKeys = cache.keys.toSet(),
        )

    companion object {
        /**
         * Constructor-default joint floor (R1). Live Host caps come from
         * [PreviewWorkingSetBudget] for the current preview long-edge.
         */
        const val SOURCE_AND_PREVIEW_BYTES_MAX: Long = 64L * 1024 * 1024
        const val FILMSTRIP_BYTES_MAX: Long = 8L * 1024 * 1024
        /**
         * Entry cap stays 48; byte caps follow the current preview long-edge.
         */
        const val DEFAULT_WATERMARKED_ENTRIES_MAX: Int = 48
        const val DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX: Int = 12
        const val DEFAULT_FILMSTRIP_ENTRIES_MAX: Int = 48
        const val DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX: Int = 48
        /** Watermarked purpose floor — 720 panes stay at 48 MiB. */
        const val DEFAULT_WATERMARKED_BYTES_MAX: Long = 48L * 1024 * 1024
        const val DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX: Long = 12L * 1024 * 1024
        const val DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX: Long = 8L * 1024 * 1024

        fun approxBytes(bitmap: ImageBitmap): Long =
            bitmap.width.toLong().coerceAtLeast(0L) * bitmap.height.toLong().coerceAtLeast(0L) * 4L
    }
}
