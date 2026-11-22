package me.rosuh.easywatermark.data.repo

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import me.rosuh.easywatermark.data.model.StickerCatalog

/**
 * Desktop watermark-icon persistence — the testable extraction of the "Open icon…"
 * Copy-then-prune logic (formerly inline in `DesktopWindow.kt`). The Desktop sibling of iOS * `IosIconPersistence`: a picked icon is copied into app-private storage so Image-mode
 * survives the source icon moving/renaming/deleting and is machine-portable, instead of persisting the
 * user's original absolute path.
 *
 * Behavior-preserving vs the accepted inline logic; gated by `DesktopIconPersistenceTest`
 * (`:shared:desktopTest`, which runs in CI).
 */
object DesktopIconPersistence {

    /**
 * Copy [source] into [iconsDir] as the single bounded helper-owned file `icon.<ext>` and return it.
 *
 * - Creates [iconsDir] if missing; extension = `source.extension` lowercased (blank → `png`).
 * - **No-op** if [source] already IS the target copy (`canonicalFile` equal) — never deletes/recopies it.
 * - Else copies to `incoming.tmp` then **atomically** moves it into place
 * (`ATOMIC_MOVE` + `REPLACE_EXISTING`), so a failed source read never destroys a prior copy.
 * - Prunes any OTHER files under [iconsDir] only AFTER the target exists → bounded to one file.
 * - Throws on failure (callers surface it, e.g. the Desktop window's `try/catch → "Failed: …"`).
     */
    fun persistIcon(source: File, iconsDir: File): File {
        iconsDir.mkdirs()
        val ext = source.extension.lowercase().ifBlank { "png" }
        val target = File(iconsDir, "icon.$ext")
        if (source.canonicalFile != target.canonicalFile) {
            val tmp = File(iconsDir, "incoming.tmp")
            source.copyTo(tmp, overwrite = true)
            // ATOMIC_MOVE so `target` is never left half-written; if the OS rejects atomic moves it
            // throws (caller reports the failure) with the prior copy untouched (pruning hasn't run yet).
            // REPLACE_EXISTING overwrites the previous same-ext copy.
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        // Bounded: only AFTER `target` exists, drop OTHER helper-owned files
        // (File.delete() returns false on failure, never throws → no spurious exception).
        iconsDir.listFiles()?.forEach {
            if (it.canonicalFile != target.canonicalFile) it.delete()
        }
        return target
    }

    /**
     * Copy [source] to a new file `icon-[id].[ext]` and leave every other file in [iconsDir] alone.
     * [id] may only contain letters, digits, and hyphens.
     */
    fun persistLibraryIcon(source: File, iconsDir: File, id: String): File {
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' }
        require(safeId.isNotEmpty()) { "sticker id is empty" }
        iconsDir.mkdirs()
        val ext = source.extension.lowercase().ifBlank { "png" }
        val target = File(iconsDir, "icon-$safeId.$ext")
        if (source.canonicalFile != target.canonicalFile) {
            val tmp = File(iconsDir, "incoming-$safeId.tmp")
            source.copyTo(tmp, overwrite = true)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return target
    }

    /**
     * Write built-in [bytes] to a deterministic helper-owned file `icon-builtin-[key].png` inside
     * [iconsDir], replacing any prior copy atomically so repeated selections do not leave duplicates.
     */
    fun persistBuiltInPng(bytes: ByteArray, iconsDir: File, key: String): File {
        require(bytes.isNotEmpty()) { "built-in sticker bytes are empty" }
        val safeKey = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safeKey.isNotEmpty()) { "built-in sticker key is empty" }
        iconsDir.mkdirs()
        val target = File(iconsDir, "icon-builtin-$safeKey.png")
        val tmp = File(iconsDir, "incoming-builtin-$safeKey.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    fun readCatalogText(iconsDir: File): String {
        val file = File(iconsDir, StickerCatalog.FILE_NAME)
        return if (file.isFile) file.readText() else ""
    }

    fun writeCatalogText(iconsDir: File, text: String) {
        iconsDir.mkdirs()
        File(iconsDir, StickerCatalog.FILE_NAME).writeText(text)
    }
}
