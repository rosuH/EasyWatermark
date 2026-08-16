package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One host-owned source/preview cache state machine.
 *
 * Generic over the platform bitmap type. Decode stays at the platform edge — this type
 * never opens files or ContentResolver.
 *
 * **Invariant:** a [PreviewPurpose.Watermarked] key is only path + bucket. It does **not**
 * include config or offset. ApplyConfig must [clearPurpose] Watermarked; offset commit
 * [invalidateOwnedPath] for that path. Draft frames must never be written as Watermarked.
 *
 * Every cache/in-flight/epoch/closed read or write is guarded by [mutex]. The owner completion
 * lives under [ownerScope]'s lifecycle, while individual waiters are allowed to cancel normally:
 * cancelling one visible cell never strands an in-flight map entry or forces another waiter to
 * wait in NonCancellable. `clear` and `close` both gate late completions before caching them.
 *
 * Hidden from Shared.framework (J5). Not a Swift product API.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
open class PreviewImageRepository<T : Any>(
    private val ownerScope: CoroutineScope,
    private val approxBytes: (T) -> Long,
    sourceAndPreviewBytesMax: Long = SOURCE_AND_PREVIEW_BYTES_MAX,
    private val filmstripBytesMax: Long = FILMSTRIP_BYTES_MAX,
    watermarkedEntriesMax: Int = DEFAULT_WATERMARKED_ENTRIES_MAX,
    sourcePlaceholderEntriesMax: Int = DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX,
    private val filmstripEntriesMax: Int = DEFAULT_FILMSTRIP_ENTRIES_MAX,
    private val exportThumbnailEntriesMax: Int = DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX,
    private val sourceFastPathEntriesMax: Int = DEFAULT_SOURCE_FAST_PATH_ENTRIES_MAX,
    watermarkedBytesMax: Long = DEFAULT_WATERMARKED_BYTES_MAX,
    sourcePlaceholderBytesMax: Long = DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX,
    private val exportThumbnailBytesMax: Long = DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX,
    private val sourceFastPathBytesMax: Long = DEFAULT_SOURCE_FAST_PATH_BYTES_MAX,
) {
    private var sourceAndPreviewBytesMax: Long = sourceAndPreviewBytesMax
    private var watermarkedEntriesMax: Int = watermarkedEntriesMax
    private var sourcePlaceholderEntriesMax: Int = sourcePlaceholderEntriesMax
    private var watermarkedBytesMax: Long = watermarkedBytesMax
    private var sourcePlaceholderBytesMax: Long = sourcePlaceholderBytesMax

    private data class InFlight<T>(
        val epoch: Long,
        val deferred: CompletableDeferred<T?>,
    )

    private val mutex = Mutex()
    private val cache = linkedMapOf<PreviewKey, T>()
    private val inFlight = mutableMapOf<PreviewKey, InFlight<T>>()
    private var epoch = 0L
    private var closed = false

    private val completionJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val completionScope = CoroutineScope(ownerScope.coroutineContext + completionJob)
    /** Contended close only — never a child of the Host Job. */
    private val orphanCloseJob = SupervisorJob()

    suspend fun load(
        key: PreviewKey,
        decoder: suspend () -> T?,
    ): T? {
        require(key.ownedPath.isNotBlank()) { "PreviewImageRepository: blank owned path" }
        require(key.pixelBucket > 0) { "PreviewImageRepository: non-positive bucket" }
        val deferred: Deferred<T?> = mutex.withLock {
            if (closed) return@withLock completedDeferred(null)
            touchLocked(key)?.let { return@withLock completedDeferred(it) }
            inFlight[key]?.deferred ?: startCompletionLocked(key, decoder)
        }
        return deferred.await()
    }

    /** Cache-only lookup; unlike [load], this never begins a decode. */
    suspend fun cached(key: PreviewKey): T? = mutex.withLock {
        if (closed) null else touchLocked(key)
    }

    /**
     * Non-suspending cache peek for composition hot paths.
     * Uncontended: returns the cached value. Contended/closed: null (caller falls through to load).
     * Never starts a decode and never blocks the main thread on the repository mutex.
     */
    fun peekCached(key: PreviewKey): T? {
        if (closed) return null
        if (!mutex.tryLock()) return null
        try {
            if (closed) return null
            return touchLocked(key)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * LRU recency update: re-insert a hit at the tail of the insertion-ordered [cache].
     */
    private fun touchLocked(key: PreviewKey): T? {
        val value = cache.remove(key) ?: return null
        cache[key] = value
        return value
    }

    suspend fun invalidate(key: PreviewKey) {
        mutex.withLock {
            cache.remove(key)
            inFlight.remove(key)?.deferred?.cancel()
        }
    }

    /** Remove every pixel bucket for one source/purpose (for example after an offset commit). */
    suspend fun invalidateOwnedPath(ownedPath: String, purpose: PreviewPurpose) {
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
    suspend fun clearPurpose(purpose: PreviewPurpose) {
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

    /**
     * Drop every [purpose] entry except those whose [PreviewKey.ownedPath] is in [keepOwnedPaths].
     * Used on Android trim: drop neighbor Sources, keep the focus Source.
     */
    suspend fun evictPurposeExcept(purpose: PreviewPurpose, keepOwnedPaths: Set<String>) {
        mutex.withLock {
            cache.keys
                .filter { it.purpose == purpose && it.ownedPath !in keepOwnedPaths }
                .toList()
                .forEach(cache::remove)
            inFlight.keys
                .filter { it.purpose == purpose && it.ownedPath !in keepOwnedPaths }
                .toList()
                .forEach { key -> inFlight.remove(key)?.deferred?.cancel() }
        }
    }

    /** Permanent Host teardown. Subsequent requests fail closed and owner work is cancelled. */
    suspend fun close() {
        val waiters = mutex.withLock { markClosedAndDrainLocked() }
        waiters.forEach { it.complete(null) }
        completionJob.cancel()
        orphanCloseJob.cancel()
    }

    /**
     * Owner lifecycle bridge for non-suspending UIKit/Compose disposal hooks.
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

    private fun markClosedAndDrainLocked(): List<CompletableDeferred<T?>> {
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
    suspend fun withMutexHeldForTests(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }

    fun clearFromOwner() {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) { clear() }
    }

    fun clearPurposeFromOwner(purpose: PreviewPurpose) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) { clearPurpose(purpose) }
    }

    fun evictPurposeExceptFromOwner(purpose: PreviewPurpose, keepOwnedPaths: Set<String>) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            evictPurposeExcept(purpose, keepOwnedPaths)
        }
    }

    fun invalidateFromOwner(key: PreviewKey) {
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
        watermarkedEntriesMax = caps.watermarkedEntriesMax
        sourcePlaceholderEntriesMax = caps.sourceEntriesMax
        if (!closed) {
            enforceBudgetsLocked()
        }
    }

    fun invalidateOwnedPathFromOwner(ownedPath: String, purpose: PreviewPurpose) {
        completionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            invalidateOwnedPath(ownedPath, purpose)
        }
    }

    /** Test/host cache insertion for values already decoded by a renderer edge. */
    suspend fun putForTests(key: PreviewKey, value: T) {
        mutex.withLock {
            if (closed) return
            cache[key] = value
            enforceBudgetsLocked()
        }
    }

    /** Synchronous test seam only; it never waits for a mutex or performs IO. */
    fun putForTestsImmediate(key: PreviewKey, value: T) {
        check(mutex.tryLock()) { "PreviewImageRepository test seam contended" }
        try {
            if (closed) return
            cache[key] = value
            enforceBudgetsLocked()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun snapshot(): PreviewRepositorySnapshot = mutex.withLock {
        snapshotLocked()
    }

    /**
     * Synchronous test seam only; production reads through suspend APIs.
     * When the owner completion holds the mutex, return an empty-cache snapshot rather than
     * throwing — Host path identity remains authoritative.
     */
    fun snapshotForTestsImmediate(): PreviewRepositorySnapshot {
        if (!mutex.tryLock()) {
            return PreviewRepositorySnapshot(
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
        key: PreviewKey,
        decoder: suspend () -> T?,
    ): CompletableDeferred<T?> {
        val deferred = CompletableDeferred<T?>()
        val entry = InFlight(epoch = epoch, deferred = deferred)
        inFlight[key] = entry
        completionScope.launch {
            val decoded = runCatching { decoder() }.getOrNull()
            val toComplete: T? = withContext(NonCancellable) {
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

    private fun completedDeferred(value: T?): CompletableDeferred<T?> =
        CompletableDeferred<T?>().also { it.complete(value) }

    private fun enforceBudgetsLocked() {
        evictToEntryCapLocked(PreviewPurpose.Watermarked, watermarkedEntriesMax)
        evictToEntryCapLocked(PreviewPurpose.SourcePlaceholder, sourcePlaceholderEntriesMax)
        evictToEntryCapLocked(PreviewPurpose.Filmstrip, filmstripEntriesMax)
        evictToEntryCapLocked(PreviewPurpose.ExportThumbnail, exportThumbnailEntriesMax)
        evictToEntryCapLocked(PreviewPurpose.SourceFastPath, sourceFastPathEntriesMax)
        evictLeastRecentlyUsedMatchingLocked(
            maxBytes = watermarkedBytesMax,
            matches = { it.purpose == PreviewPurpose.Watermarked },
        )
        evictLeastRecentlyUsedMatchingLocked(
            maxBytes = sourcePlaceholderBytesMax,
            matches = { it.purpose == PreviewPurpose.SourcePlaceholder },
        )
        evictLeastRecentlyUsedMatchingLocked(
            maxBytes = exportThumbnailBytesMax,
            matches = { it.purpose == PreviewPurpose.ExportThumbnail },
        )
        evictLeastRecentlyUsedMatchingLocked(
            maxBytes = sourceFastPathBytesMax,
            matches = { it.purpose == PreviewPurpose.SourceFastPath },
        )
        evictJointNonFilmstripLocked(sourceAndPreviewBytesMax)
        evictLeastRecentlyUsedMatchingLocked(
            maxBytes = filmstripBytesMax,
            matches = { it.purpose == PreviewPurpose.Filmstrip },
        )
    }

    private fun evictToEntryCapLocked(purpose: PreviewPurpose, maxEntries: Int) {
        while (cache.keys.count { it.purpose == purpose } > maxEntries) {
            val leastRecent = cache.keys.firstOrNull { it.purpose == purpose } ?: return
            cache.remove(leastRecent)
        }
    }

    private fun evictLeastRecentlyUsedMatchingLocked(
        maxBytes: Long,
        matches: (PreviewKey) -> Boolean,
    ) {
        while (bytesForLocked(matches) > maxBytes) {
            val leastRecent = cache.keys.firstOrNull(matches) ?: return
            cache.remove(leastRecent)
        }
    }

    /**
     * While joint non-filmstrip bytes exceed [maxBytes], remove the least recently used entry in
     * priority order ExportThumbnail → SourceFastPath → Watermarked → SourcePlaceholder
     * (never Filmstrip here).
     *
     * Watermarked goes before SourcePlaceholder because the two cost very different amounts to
     * rebuild: a Watermarked frame whose Source is still resident is a compose, while a Source is
     * a cold decode (~94% of a 12MP paint on device).
     */
    private fun evictJointNonFilmstripLocked(maxBytes: Long) {
        fun isNonFilmstrip(key: PreviewKey) =
            key.purpose != PreviewPurpose.Filmstrip
        while (bytesForLocked(::isNonFilmstrip) > maxBytes) {
            val leastRecent = cache.keys.firstOrNull {
                it.purpose == PreviewPurpose.ExportThumbnail
            } ?: cache.keys.firstOrNull {
                it.purpose == PreviewPurpose.SourceFastPath
            } ?: cache.keys.firstOrNull {
                it.purpose == PreviewPurpose.Watermarked
            } ?: cache.keys.firstOrNull {
                it.purpose == PreviewPurpose.SourcePlaceholder
            } ?: return
            cache.remove(leastRecent)
        }
    }

    private fun bytesForLocked(matches: (PreviewKey) -> Boolean): Long =
        cache.entries
            .asSequence()
            .filter { matches(it.key) }
            .sumOf { (_, value) -> approxBytes(value) }

    private fun snapshotLocked(): PreviewRepositorySnapshot =
        PreviewRepositorySnapshot(
            cachedEntries = cache.size,
            inFlightEntries = inFlight.size,
            previewBytes = bytesForLocked { it.purpose != PreviewPurpose.Filmstrip },
            filmstripBytes = bytesForLocked { it.purpose == PreviewPurpose.Filmstrip },
            closed = closed,
            watermarkedEntries = cache.keys.count { it.purpose == PreviewPurpose.Watermarked },
            sourcePlaceholderEntries = cache.keys.count {
                it.purpose == PreviewPurpose.SourcePlaceholder
            },
            filmstripEntries = cache.keys.count { it.purpose == PreviewPurpose.Filmstrip },
            exportThumbnailEntries = cache.keys.count {
                it.purpose == PreviewPurpose.ExportThumbnail
            },
            watermarkedBytes = bytesForLocked { it.purpose == PreviewPurpose.Watermarked },
            sourcePlaceholderBytes = bytesForLocked {
                it.purpose == PreviewPurpose.SourcePlaceholder
            },
            exportThumbnailBytes = bytesForLocked {
                it.purpose == PreviewPurpose.ExportThumbnail
            },
            cachedKeys = cache.keys.toSet(),
        )

    companion object {
        const val SOURCE_AND_PREVIEW_BYTES_MAX: Long = 64L * 1024 * 1024
        const val FILMSTRIP_BYTES_MAX: Long = 8L * 1024 * 1024
        const val DEFAULT_WATERMARKED_ENTRIES_MAX: Int = 48
        const val DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX: Int = 12
        const val DEFAULT_FILMSTRIP_ENTRIES_MAX: Int = 48
        const val DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX: Int = 48
        /** Focus ±1 chrome only (ADR-0029). */
        const val DEFAULT_SOURCE_FAST_PATH_ENTRIES_MAX: Int = 3
        const val DEFAULT_WATERMARKED_BYTES_MAX: Long = 48L * 1024 * 1024
        const val DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX: Long = 12L * 1024 * 1024
        const val DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX: Long = 8L * 1024 * 1024
        const val DEFAULT_SOURCE_FAST_PATH_BYTES_MAX: Long = 12L * 1024 * 1024

        fun approxImageBitmapBytes(bitmap: ImageBitmap): Long =
            bitmap.width.toLong().coerceAtLeast(0L) * bitmap.height.toLong().coerceAtLeast(0L) * 4L
    }
}
