package me.rosuh.easywatermark.render

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * G1: crash-atomic publish of complete byte arrays to a public [target] path.
 *
 * Algorithm (target-directory local):
 * 1. Create a sibling temp under [File.getParentFile] (`.ewm-<uuid>.tmp`).
 * 2. Write all bytes; [java.io.FileDescriptor.sync] where available.
 * 3. [Files.move] with [StandardCopyOption.ATOMIC_MOVE] + [StandardCopyOption.REPLACE_EXISTING].
 *    If [AtomicMoveNotSupportedException], fall back to [StandardCopyOption.REPLACE_EXISTING]
 *    only (documented residual: not fully crash-atomic on that FS).
 * 4. On any failure: delete temp; leave any pre-existing [target] bytes intact.
 *
 * Callers must pass the **final** public path (exact Save As or unique export name).
 * Preview/export product code goes through [DesktopRenderSaveSpine.renderAndSave].
 */
object DesktopAtomicFileWrite {

    /**
     * Optional fault-injection points for tests. Production uses defaults (no-ops).
     * Hooks run in order around the durable write stages.
     */
    data class Hooks(
        val beforeWrite: () -> Unit = {},
        val afterWriteBeforeSync: () -> Unit = {},
        val afterSyncBeforeMove: () -> Unit = {},
        val afterMove: () -> Unit = {},
    )

    /**
     * Publish [bytes] to [target] crash-atomically.
     *
     * @throws Exception propagated from hooks / IO; temp cleaned best-effort.
     */
    fun publish(
        target: File,
        bytes: ByteArray,
        hooks: Hooks = Hooks(),
    ) {
        val parent = requireNotNull(target.parentFile) {
            "DesktopAtomicFileWrite: target has no parent directory: $target"
        }
        if (!parent.exists()) {
            check(parent.mkdirs() || parent.isDirectory) {
                "DesktopAtomicFileWrite: unable to create parent directory: $parent"
            }
        }
        val temp = File(parent, ".ewm-${UUID.randomUUID()}.tmp")
        var moved = false
        try {
            hooks.beforeWrite()
            FileOutputStream(temp).use { fos ->
                fos.write(bytes)
                hooks.afterWriteBeforeSync()
                fos.fd.sync()
                hooks.afterSyncBeforeMove()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Residual: some FSes reject ATOMIC_MOVE; REPLACE_EXISTING is best-effort.
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            moved = true
            hooks.afterMove()
        } catch (t: Throwable) {
            // Never leave a public half-file from a failed publish: only temp may be partial.
            runCatching { if (temp.exists()) temp.delete() }
            throw t
        } finally {
            if (!moved) {
                runCatching { if (temp.exists()) temp.delete() }
            }
        }
    }
}
