@file:OptIn(ExperimentalForeignApi::class)

package me.rosuh.easywatermark.data.repo

import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.data.model.MediaRef
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
 * S4d-116: iOS **app-private icon-bytes persistence** (Option A from the S4d-114 readiness pack).
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
 * used by the S4d-115 icon render path. iosMain-only (not compiled for Android/`:app` or desktop).
 *
 * ## Failure mode: loud, never silent
 * Empty bytes ([writeIconBytes]) and an unreadable path ([readIconBytes]) throw rather than creating or
 * propagating an unusable icon.
 */
object IosIconPersistence {

    /** Helper-owned subdirectory under `NSDocumentDirectory` (ownership boundary for [deleteIfOwned]). */
    const val ICON_DIR_NAME: String = "watermark_icons"

    /** Helper-owned filename prefix (ownership boundary; a unique `NSUUID` is appended per write). */
    const val ICON_FILE_PREFIX: String = "icon_"

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
     * to persist as a [MediaRef]). Throws [IllegalArgumentException] for empty bytes and
     * [IllegalStateException] if the write fails.
     */
    fun writeIconBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "IosIconPersistence: refusing to persist empty icon bytes" }
        val path = ownedPrefix() + NSUUID().UUIDString()
        val ok = IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        check(ok) { "IosIconPersistence: failed to write ${bytes.size} icon bytes to '$path'" }
        return path
    }

    /**
     * Read the icon bytes back from a persisted [ref]'s path. Throws [IllegalStateException] if the file is
     * missing/unreadable (e.g. after cleanup), so callers fail loudly instead of rendering a blank icon.
     */
    fun readIconBytes(ref: MediaRef): ByteArray {
        val data: NSData = NSData.dataWithContentsOfFile(ref.value)
            ?: error("IosIconPersistence: could not read icon bytes at '${ref.value}'")
        return IosByteArrayInterop.fromNSData(data)
    }

    /**
     * True iff [path] is a helper-owned icon file: it starts with the owned prefix
     * (`<docs>/watermark_icons/icon_`) **and** the remainder is a single generated filename — non-empty
     * and containing **no path separator**. The no-`/` rule is what makes ownership safe: a generated path
     * is `prefix + NSUUID` (no `/`), whereas a traversal (`icon_/../../foreign`) or a nested/sibling path
     * (`icon_x/foreign`) has a `/` after the prefix and is therefore NOT owned. So [deleteIfOwned] can
     * never escape the owned directory or delete an arbitrary path that merely shares the prefix.
     */
    fun isOwned(path: String): Boolean {
        val prefix = ownedPrefix()
        if (!path.startsWith(prefix)) return false
        val fileName = path.substring(prefix.length)
        return fileName.isNotEmpty() && !fileName.contains('/')
    }

    /**
     * Best-effort delete of a PRIOR helper-owned icon file on replacement. **Only** deletes paths this
     * helper owns ([isOwned]); a foreign/empty path is ignored, so arbitrary paths are never removed.
     */
    fun deleteIfOwned(path: String) {
        if (!isOwned(path)) return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
