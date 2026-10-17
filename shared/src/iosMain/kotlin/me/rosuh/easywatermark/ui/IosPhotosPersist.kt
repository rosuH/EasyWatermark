package me.rosuh.easywatermark.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.coroutines.resume

/**
 * Swift↔Kotlin Photos edge (D4).
 *
 * Invokes [onComplete] **exactly once** after Photos persistence finishes (success or failure).
 * Production Swift wraps `PHPhotoLibrary.performChanges`; tests inject fakes.
 */
/** J5: Photos save edge adapter — not a Swift product type. */
internal fun interface IosPhotosSaveEdge {
    fun save(bytes: ByteArray, onComplete: (success: Boolean, message: String?) -> Unit)
}

/** Result of applying Photos persistence after Session render successes. */
internal data class PhotosPersistBatchResult(
    /** Items with Session [JobState.Success] and a non-blank result path. */
    val renderSuccessCount: Int,
    /** Items where Photos edge reported success after await. */
    val persistedCount: Int,
    /** Successful Photos writes, retained for retry within the same sheet batch. */
    val persistedPaths: Set<String> = emptySet(),
    /** Per-item Photos failure messages (order of failed Photos attempts). */
    val photosFailureMessages: List<String> = emptyList(),
)

/**
 * For each Session render success, load encoded bytes and **await** Photos save.
 * Does not mutate [ImageInfo.jobState] on Photos failure (render success is preserved).
 */
internal suspend fun persistRenderSuccessesToPhotos(
    images: List<ImageInfo>,
    loadBytes: (filePath: String) -> ByteArray?,
    photosSave: IosPhotosSaveEdge,
    shouldStop: () -> Boolean = { false },
    alreadyPersistedPaths: Set<String> = emptySet(),
): PhotosPersistBatchResult {
    val renderSuccessCount = images.count {
        it.jobState is JobState.Success &&
            !(it.result?.data as? MediaRef)?.value.isNullOrBlank()
    }
    val persistedPaths = alreadyPersistedPaths.toMutableSet()
    val failures = mutableListOf<String>()
    for (info in images) {
        // A submitted PhotoKit write must finish; cancellation stops the next submission.
        if (shouldStop()) break
        val ref = (info.result?.data as? MediaRef)?.value
        if (info.jobState !is JobState.Success || ref.isNullOrBlank()) continue
        if (ref in persistedPaths) continue
        val encodedBytes = loadBytes(ref) ?: run {
            failures += "missing export bytes for $ref"
            continue
        }
        val (ok, message) = awaitPhotosSave(photosSave, encodedBytes)
        if (ok) {
            persistedPaths += ref
        } else {
            failures += message ?: "Photos save failed"
        }
    }
    return PhotosPersistBatchResult(
        renderSuccessCount = renderSuccessCount,
        persistedCount = persistedPaths.size,
        persistedPaths = persistedPaths,
        photosFailureMessages = failures,
    )
}

internal suspend fun awaitPhotosSave(
    photosSave: IosPhotosSaveEdge,
    bytes: ByteArray,
): Pair<Boolean, String?> = suspendCancellableCoroutine { cont ->
    photosSave.save(bytes) { success, message ->
        if (cont.isActive) {
            cont.resume(success to message)
        }
    }
}

/** Status line for save sheet after render + Photos phase (D4 honesty). */
/** J5: status helper for host sheet — not called from Swift. */
internal fun photosPersistStatusLine(
    batchSize: Int,
    result: PhotosPersistBatchResult,
): String = when {
    result.persistedCount > 0 && result.photosFailureMessages.isEmpty() ->
        "Saved ${result.persistedCount}/$batchSize to Photos"
    result.persistedCount > 0 ->
        "Saved ${result.persistedCount}/$batchSize to Photos " +
            "(${result.photosFailureMessages.size} Photos failed)"
    result.renderSuccessCount > 0 ->
        "Render ok but Photos failed: " +
            (result.photosFailureMessages.firstOrNull() ?: "unknown")
    else -> "Nothing to export"
}
