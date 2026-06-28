package me.rosuh.easywatermark.data.repo

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * S4d-221: Desktop watermark-icon persistence — the testable extraction of the S4d-219 "Open icon…"
 * copy-then-prune logic (formerly inline in `DesktopWindow.kt`). The Desktop sibling of iOS
 * `IosIconPersistence` (S4d-116): a picked icon is copied into app-private storage so Image-mode
 * survives the source icon moving/renaming/deleting and is machine-portable, instead of persisting the
 * user's original absolute path.
 *
 * Behavior-preserving vs the accepted S4d-219 inline logic; gated by `DesktopIconPersistenceTest`
 * (`:shared:desktopTest`, which runs in CI).
 */
object DesktopIconPersistence {

    /**
     * Copy [source] into [iconsDir] as the single bounded helper-owned file `icon.<ext>` and return it.
     *
     * - Creates [iconsDir] if missing; extension = `source.extension` lowercased (blank → `png`).
     * - **No-op** if [source] already IS the target copy (`canonicalFile` equal) — never deletes/recopies it.
     * - Else copies to `incoming.tmp` then **atomically** moves it into place
     *   (`ATOMIC_MOVE` + `REPLACE_EXISTING`), so a failed source read never destroys a prior copy.
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
}
