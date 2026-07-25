package me.rosuh.easywatermark.platform

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * E2: durable app-owned staging for inbound share / picker URIs that may die after process death
 * or grant revoke. Mirrors [AndroidIconPersistence] publish-or-delete staging discipline.
 *
 * Bytes land under `filesDir/share_sources/`; published refs are FileProvider `content://` URIs
 * under the `share_sources` path segment so decode continues to use ContentResolver.
 */
class AndroidShareStaging internal constructor(
    private val stageDirectory: File,
    private val authority: String,
    private val openSource: (Uri) -> InputStream?,
    private val sourceMimeType: (Uri) -> String?,
    private val contentUriForFile: (File) -> Uri,
    private val nextId: () -> String,
) {

    constructor(context: Context) : this(
        stageDirectory = File(context.filesDir, STAGE_DIRECTORY_NAME),
        authority = "${BuildConfig.APPLICATION_ID}.fileprovider",
        openSource = { source -> context.contentResolver.openInputStream(source) },
        sourceMimeType = { source -> context.contentResolver.getType(source) },
        contentUriForFile = { file ->
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
        },
        nextId = { UUID.randomUUID().toString() },
    )

    /**
     * Copy [source] into the private stage directory and return an app-owned [MediaRef].
     * Partial / zero-byte copies never become visible.
     */
    suspend fun copyToOwnedRef(source: Uri): Result<MediaRef> = withContext(Dispatchers.IO) {
        try {
            check(stageDirectory.exists() || stageDirectory.mkdirs()) {
                "Unable to create share stage directory"
            }
            val directory = stageDirectory.canonicalFile
            val extension = extensionFor(sourceMimeType(source), source)
            val id = nextId().takeIf(::isSafeId) ?: error("Unsafe share identity")
            val destination = File(directory, "share-$id.$extension").canonicalFile
            check(destination.parentFile == directory) { "Share destination escaped private directory" }
            check(!destination.exists()) { "Share destination collision" }

            val staging = File.createTempFile("share-staging-", ".tmp", directory)
            var published = false
            try {
                val copied = openSource(source)?.use { input ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            count += read
                        }
                        output.fd.sync()
                        count
                    }
                } ?: error("Shared image is unreadable")
                check(copied > 0L) { "Shared image is empty" }
                check(staging.renameTo(destination)) { "Unable to publish private share copy" }

                val uri = contentUriForFile(destination)
                check(uri.scheme == "content" && uri.authority == authority) {
                    "Private share URI has unexpected authority"
                }
                published = true
                Result.success(MediaRef(uri.toString()))
            } finally {
                staging.delete()
                if (!published) destination.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    /** Stage many sources; fails closed if any item fails (partial list not returned). */
    suspend fun copyAllToOwnedRefs(sources: List<Uri>): Result<List<MediaRef>> {
        if (sources.isEmpty()) return Result.success(emptyList())
        val owned = ArrayList<MediaRef>(sources.size)
        try {
            for (source in sources) {
                val ref = copyToOwnedRef(source).getOrElse { err ->
                    owned.forEach { deleteIfOwned(it) }
                    return Result.failure(err)
                }
                owned.add(ref)
            }
            return Result.success(owned)
        } catch (cancelled: CancellationException) {
            owned.forEach { deleteIfOwned(it) }
            throw cancelled
        }
    }

    fun deleteIfOwned(ref: MediaRef): Boolean = ownedFile(ref)?.delete() == true

    /** True when [ref] points at a still-readable file managed by this stager. */
    fun isOwnedReadable(ref: MediaRef): Boolean {
        val file = ownedFile(ref) ?: return false
        return file.isFile && file.length() > 0L
    }

    fun pruneExcept(keep: Collection<MediaRef>) {
        val directory = runCatching { stageDirectory.canonicalFile }.getOrNull() ?: return
        val keepFiles = keep.mapNotNull { ownedFile(it)?.canonicalFile }.toSet()
        directory.listFiles()?.forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile == directory &&
                isManagedFileName(canonical.name) &&
                canonical !in keepFiles
            ) {
                canonical.delete()
            }
        }
    }

    private fun ownedFile(ref: MediaRef): File? {
        if (ref.isEmpty()) return null
        val uri = runCatching { Uri.parse(ref.value) }.getOrNull() ?: return null
        if (uri.scheme != "content" || uri.authority != authority) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != STAGE_PROVIDER_ROOT) return null
        val fileName = segments[1]
        if (!isManagedFileName(fileName)) return null
        val directory = runCatching { stageDirectory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(directory, fileName).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.parentFile == directory }
    }

    private fun extensionFor(mimeType: String?, source: Uri): String {
        when (mimeType?.lowercase()) {
            "image/jpeg", "image/jpg" -> return "jpg"
            "image/png" -> return "png"
            "image/webp" -> return "webp"
            "image/gif" -> return "gif"
            "image/heic" -> return "heic"
            "image/heif" -> return "heif"
            "image/avif" -> return "avif"
        }
        val last = source.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (last.length in 2..5 && last.all { it.isLetterOrDigit() }) last else "img"
    }

    private fun isSafeId(value: String): Boolean =
        value.isNotBlank() && value.length <= 96 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isManagedFileName(value: String): Boolean =
        value.startsWith("share-") && value.length <= 128 && value.none { it == '/' || it == '\\' }

    companion object {
        const val STAGE_DIRECTORY_NAME = "share_sources"
        const val STAGE_PROVIDER_ROOT = "share_sources"
    }
}
