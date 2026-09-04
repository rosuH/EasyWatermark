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

internal interface DurableIconStore {
    suspend fun copyToOwnedRef(source: Uri): Result<MediaRef>
    fun deleteIfOwned(ref: MediaRef): Boolean
    fun pruneExcept(keep: MediaRef)
}

/** Android-only durable store for a user-selected watermark icon. */
class AndroidIconPersistence internal constructor(
    private val iconDirectory: File,
    private val authority: String,
    private val openSource: (Uri) -> InputStream?,
    private val sourceMimeType: (Uri) -> String?,
    private val contentUriForFile: (File) -> Uri,
    private val nextId: () -> String,
) : DurableIconStore {

    constructor(context: Context) : this(
        iconDirectory = File(context.filesDir, ICON_DIRECTORY_NAME),
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
     * Copies [source] into the dedicated internal directory, then publishes a unique app-owned
     * `content://` ref. Partial and zero-byte copies never become visible.
     */
    override suspend fun copyToOwnedRef(source: Uri): Result<MediaRef> = withContext(Dispatchers.IO) {
        try {
            check(iconDirectory.exists() || iconDirectory.mkdirs()) {
                "Unable to create private icon directory"
            }
            val directory = iconDirectory.canonicalFile
            val extension = extensionFor(sourceMimeType(source))
            val id = nextId().takeIf(::isSafeId) ?: error("Unsafe icon identity")
            val destination = File(directory, "icon-$id.$extension").canonicalFile
            check(destination.parentFile == directory) { "Icon destination escaped private directory" }
            check(!destination.exists()) { "Icon destination collision" }

            val staging = File.createTempFile("icon-staging-", ".tmp", directory)
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
                } ?: error("Selected icon is unreadable")
                check(copied > 0L) { "Selected icon is empty" }
                check(staging.renameTo(destination)) { "Unable to publish private icon copy" }

                val uri = contentUriForFile(destination)
                check(uri.scheme == "content" && uri.authority == authority) {
                    "Private icon URI has unexpected authority"
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

    /** Deletes [ref] only when it belongs to this helper's exact authority and directory. */
    override fun deleteIfOwned(ref: MediaRef): Boolean = ownedFile(ref)?.delete() == true

    /** Removes crash-orphaned helper files while preserving [keep] when it is app-owned. */
    override fun pruneExcept(keep: MediaRef) {
        val directory = runCatching { iconDirectory.canonicalFile }.getOrNull() ?: return
        val keepFile = ownedFile(keep)?.let { runCatching { it.canonicalFile }.getOrNull() }
        directory.listFiles()?.forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile == directory &&
                isManagedFileName(canonical.name) &&
                canonical != keepFile
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
        if (segments.size != 2 || segments[0] != ICON_PROVIDER_ROOT) return null
        val fileName = segments[1]
        if (!isManagedFileName(fileName)) return null
        val directory = runCatching { iconDirectory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(directory, fileName).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.parentFile == directory }
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        else -> "img"
    }

    private fun isSafeId(value: String): Boolean =
        value.isNotBlank() && value.length <= 96 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isManagedFileName(value: String): Boolean =
        value.startsWith("icon-") && value.length <= 128 && value.none { it == '/' || it == '\\' }

    private companion object {
        const val ICON_DIRECTORY_NAME = "watermark_icons"
        const val ICON_PROVIDER_ROOT = "watermark_icons"
    }
}
