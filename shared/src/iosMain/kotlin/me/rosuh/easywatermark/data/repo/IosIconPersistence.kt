@file:OptIn(ExperimentalForeignApi::class)

package me.rosuh.easywatermark.data.repo

import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.StickerCatalog
import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS **app-private icon-bytes persistence** (Option A from the readiness pack).
 *
 * ## Why this exists
 * On Android the watermark `iconUri` is a `content://` URI that `ContentResolver` re-opens at every render.
 * iOS has no `ContentResolver`, and a `PhotosPicker` result is **transient in-memory `Data`** — it is not a
 * durably re-openable handle. Because the watermark **config** (icon ref + `markMode = Image`) is persisted
 * in DataStore and must survive relaunch, the icon **pixels** must survive too. So picked icon bytes are
 * **copied into an app-private file** and that file **path** is persisted as the [MediaRef]. This mirrors
 * how the Android main-image flow already stores a compressed-file URI, and keeps `MediaRef` unchanged (the
 * string just points at app-private storage instead of a content URI) — no DataStore migration.
 *
 * ## Helper-owned storage (safe cleanup)
 * Files live under a dedicated subdirectory ([ICON_DIR_NAME]) of `NSDocumentDirectory`, with a fixed
 * filename [ICON_FILE_PREFIX]. [deleteIfOwned] deletes **only** paths inside that location with that prefix,
 * so a stale/foreign `MediaRef` (e.g. an Android content URI, or any arbitrary path) is never removed.
 *
 * ## Boundaries / dependencies
 * Pure Foundation + Kotlin/Native interop (`platform.Foundation`, the existing [IosByteArrayInterop]
 * `memcpy` bridge) — **no new dependency**, no compose-resources. commonMain stays **decode-free**: this
 * helper only stores/loads raw bytes; decoding bytes → `ImageBitmap` remains the `IosImageDecoder` boundary
 * used by the icon render path. iosMain-only (not compiled for Android/`:app` or desktop).
 *
 * ## Failure mode: loud, never silent
 * Empty bytes ([writeIconBytes]) and an unreadable path ([readIconBytes]) throw rather than creating or
 * propagating an unusable icon.
 */
/** J5: icon file persistence — used via config bridge / Session, not Swift directly. */
internal object IosIconPersistence {

    /** Helper-owned subdirectory under `NSDocumentDirectory` (ownership boundary for [deleteIfOwned]). */
    const val ICON_DIR_NAME: String = "watermark_icons"

    /** Helper-owned filename prefix (ownership boundary; a unique `NSUUID` is appended per write). */
    const val ICON_FILE_PREFIX: String = "icon_"

    private const val CONTAINERS_APP_MARKER: String = "/Containers/Data/Application/"
    private const val ROTATED_DOCUMENTS_SUFFIX: String =
        "/Documents/$ICON_DIR_NAME/$ICON_FILE_PREFIX"

    /** Resolve the app's `NSDocumentDirectory` path (the same pattern as `CreateDataStore.ios.kt`). */
    private fun documentsDirPath(): String {
        val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        return requireNotNull(url?.path) { "IosIconPersistence: could not resolve NSDocumentDirectory" }
    }

    /** The helper-owned icon directory (created if missing). */
    private fun iconDirPath(): String {
        val dir = documentsDirPath() + "/" + ICON_DIR_NAME
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    /** The owned-path prefix (`<docs>/watermark_icons/icon_`) used to verify ownership before deletion. */
    private fun ownedPrefix(): String = iconDirPath() + "/" + ICON_FILE_PREFIX

    /**
 * Write non-empty icon [bytes] to a unique helper-owned file and return its absolute path (the value
 * To persist as a [MediaRef]). Throws [IllegalArgumentException] for empty bytes and * [IllegalStateException] if the write fails.
     */
    fun writeIconBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "IosIconPersistence: refusing to persist empty icon bytes" }
        val path = ownedPrefix() + NSUUID().UUIDString()
        val ok = IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        check(ok) { "IosIconPersistence: failed to write ${bytes.size} icon bytes to '$path'" }
        return path
    }

    /** Deterministic helper-owned path for built-in sticker [key]. */
    internal fun builtInIconPath(key: String): String {
        val safeKey = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safeKey.isNotEmpty()) { "IosIconPersistence: built-in sticker key is empty" }
        return ownedPrefix() + "builtin_$safeKey.png"
    }

    /**
     * Write built-in sticker [bytes] to a deterministic helper-owned file
     * `<docs>/watermark_icons/icon_builtin_[key].png`, overwriting atomically in place.
     */
    fun writeBuiltInIconBytes(key: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "IosIconPersistence: refusing to persist empty built-in icon bytes" }
        val path = builtInIconPath(key)
        val ok = IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        check(ok) { "IosIconPersistence: failed to write ${bytes.size} built-in icon bytes to '$path'" }
        return path
    }

    /**
     * Resolve a persisted helper-owned icon [path] into [currentDocsDir], relocating across iOS
     * sandbox container UUID rotations while preserving strict filename/traversal boundaries.
     */
    internal fun resolveOwnedPath(
        path: String,
        currentDocsDir: String = documentsDirPath(),
    ): String? {
        if (path.isBlank()) return null
        val normalizedDocsDir = currentDocsDir.trimEnd('/')
        if (normalizedDocsDir.isBlank()) return null
        val currentPrefix = "$normalizedDocsDir/$ICON_DIR_NAME/$ICON_FILE_PREFIX"
        val fileName = if (path.startsWith(currentPrefix)) {
            path.removePrefix(currentPrefix)
        } else {
            extractRotatedContainerFileName(path) ?: return null
        }
        if (!isValidOwnedFileName(fileName)) return null
        return "$currentPrefix$fileName"
    }

    private fun extractRotatedContainerFileName(path: String): String? {
        if (!path.startsWith("/")) return null
        val markerIndex = path.indexOf(CONTAINERS_APP_MARKER)
        if (markerIndex < 0) return null
        val prefixBeforeMarker = path.substring(0, markerIndex)
        if (prefixBeforeMarker.split('/').any { it == "." || it == ".." }) return null
        val containerStart = markerIndex + CONTAINERS_APP_MARKER.length
        val suffixIndex = path.indexOf(ROTATED_DOCUMENTS_SUFFIX, startIndex = containerStart)
        if (suffixIndex <= containerStart) return null
        val containerSegment = path.substring(containerStart, suffixIndex)
        if (containerSegment.isBlank() ||
            containerSegment.contains('/') ||
            containerSegment == "." ||
            containerSegment == ".." ||
            containerSegment.contains("..")
        ) {
            return null
        }
        return path.substring(suffixIndex + ROTATED_DOCUMENTS_SUFFIX.length)
    }

    private fun isValidOwnedFileName(fileName: String): Boolean =
        fileName.isNotEmpty() &&
            !fileName.contains('/') &&
            fileName != "." &&
            fileName != ".." &&
            !fileName.contains("..")

    /**
     * Read the icon bytes back from a persisted [ref]'s path (relocating rotated container paths
     * when owned). Throws [IllegalStateException] if the file is missing/unreadable, so callers
     * fail loudly instead of rendering a blank icon.
     */
    fun readIconBytes(ref: MediaRef): ByteArray {
        val resolved = resolveOwnedPath(ref.value) ?: ref.value
        if (resolved.isBlank()) {
            error("IosIconPersistence: could not read icon bytes at '${ref.value}'")
        }
        val data: NSData = NSData.dataWithContentsOfFile(resolved)
            ?: error("IosIconPersistence: could not read icon bytes at '$resolved'")
        return IosByteArrayInterop.fromNSData(data)
    }

    /**
     * True iff [ref] points at an existing readable icon file (after relocating rotated container
     * paths when owned).
     */
    fun canReadIcon(ref: MediaRef): Boolean {
        if (ref.value.isBlank()) return false
        val resolved = resolveOwnedPath(ref.value) ?: ref.value
        return NSFileManager.defaultManager.fileExistsAtPath(resolved)
    }

    /**
     * True iff [path] resolves to a helper-owned icon file under either the current documents
     * directory or a rotated iOS sandbox container path, with a valid single-segment filename.
     */
    fun isOwned(path: String): Boolean = resolveOwnedPath(path) != null

    /**
     * Best-effort delete of a PRIOR helper-owned icon file on replacement. **Only** deletes paths this
     * helper owns ([isOwned]); a foreign/empty path is ignored, so arbitrary paths are never removed.
     */
    fun deleteIfOwned(path: String) {
        val owned = resolveOwnedPath(path) ?: return
        NSFileManager.defaultManager.removeItemAtPath(owned, error = null)
    }

    fun readCatalogText(): String {
        val path = iconDirPath() + "/" + StickerCatalog.FILE_NAME
        val data = NSData.dataWithContentsOfFile(path) ?: return ""
        return IosByteArrayInterop.fromNSData(data).decodeToString()
    }

    fun writeCatalogText(text: String) {
        val path = iconDirPath() + "/" + StickerCatalog.FILE_NAME
        val ok = IosByteArrayInterop.toNSData(text.encodeToByteArray()).writeToFile(path, atomically = true)
        check(ok) { "IosIconPersistence: failed to write sticker catalog" }
    }

    fun loadStickers(): List<Sticker> {
        val raw = StickerCatalog.decode(readCatalogText())
        if (raw.isEmpty()) return emptyList()
        var changed = false
        val relocated = raw.map { sticker ->
            val resolved = resolveOwnedPath(sticker.icon.value)
            if (resolved != null && resolved != sticker.icon.value) {
                changed = true
                sticker.copy(icon = MediaRef(resolved))
            } else {
                sticker
            }
        }
        if (changed) {
            runCatching { writeCatalogText(StickerCatalog.encode(relocated)) }
        }
        return relocated
    }
}
