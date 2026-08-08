@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS edge: write picker-delivered source bytes to a unique durable temp path.
 *
 * Path identity is `ewm_src_` + [NSUUID] so two different payloads never share a staged path.
 * Session selection / cache invalidation remain the caller's responsibility
 * ([IosAppServices.stagePickedImagesBytes], [me.rosuh.easywatermark.ui.IosProductRootHost]).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "IosSourceStager", exact = true)
object IosSourceStager {
    /** Swift FileRepresentation copies provider files here before its closure returns. */
    internal const val PROVISIONAL_PREFIX: String = "ewm_import_provisional_"

    /**
     * Stage [bytes] atomically under a new `ewm_src_<uuid>` path.
     * @return absolute filesystem path of the staged source.
     */
    @Throws(Exception::class)
    fun stageBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "IosSourceStager.stageBytes: empty image" }
        val srcPath = NSTemporaryDirectory() + "ewm_src_" + NSUUID().UUIDString
        val wrote = IosByteArrayInterop.toNSData(bytes).writeToFile(srcPath, atomically = true)
        check(wrote) { "IosSourceStager.stageBytes: failed to write $srcPath" }
        return srcPath
    }

    /**
     * Copy a Swift-owned FileRepresentation result to an independent Session source path.
     *
     * No NSData/Kotlin ByteArray bridge participates in this production hand-off: the provider
     * temporary file was already copied by Swift inside FileRepresentation's importing closure,
     * and this copy isolates the Session lifetime from a cancelled picker generation.  The caller
     * still owns (and must delete) [ownedPath] after a successful publication acknowledgement.
     */
    internal fun adoptOwnedFile(ownedPath: String): String {
        require(isOwnedProvisionalPath(ownedPath)) {
            "IosSourceStager.adoptOwnedFile: refusing foreign path '$ownedPath'"
        }
        val manager = NSFileManager.defaultManager
        check(manager.fileExistsAtPath(ownedPath)) {
            "IosSourceStager.adoptOwnedFile: missing owned source '$ownedPath'"
        }
        val destination = NSTemporaryDirectory() + "ewm_src_" + NSUUID().UUIDString
        // Destination identity is fixed before the copy so a cancelled caller can always delete
        // the path even if the suspend boundary after copy is aborted by cancellation.
        val copied = try {
            manager.copyItemAtPath(
                srcPath = ownedPath,
                toPath = destination,
                error = null,
            )
        } catch (t: Throwable) {
            deleteQuietly(destination)
            throw t
        }
        if (!copied) {
            deleteQuietly(destination)
            error("IosSourceStager.adoptOwnedFile: copy to '$destination' failed")
        }
        return destination
    }

    /**
     * Allocate + copy under a known destination. On any failure (including cancellation after the
     * native copy returns), the destination is deleted before rethrowing / returning error.
     */
    internal fun adoptOwnedFileOrNull(ownedPath: String): String? {
        return try {
            adoptOwnedFile(ownedPath)
        } catch (_: Throwable) {
            null
        }
    }

    /** Strong ownership check before a retry/cancel path deletes a provisional source. */
    internal fun isOwnedProvisionalPath(path: String): Boolean {
        val prefix = NSTemporaryDirectory() + PROVISIONAL_PREFIX
        if (!path.startsWith(prefix)) return false
        val filename = path.removePrefix(prefix)
        return filename.isNotEmpty() && !filename.contains('/')
    }

    /**
     * True only for a source path produced by this app's staging edge.
     *
     * A progressive remove must never unlink a legacy/external [MediaRef].  Keep this check next
     * to the writer so both the byte and file-representation paths use the same ownership rule.
     */
    internal fun isOwnedSourcePath(path: String): Boolean {
        val prefix = NSTemporaryDirectory() + "ewm_src_"
        if (!path.startsWith(prefix)) return false
        val filename = path.removePrefix(prefix)
        return filename.isNotEmpty() && !filename.contains('/')
    }

    /** Test-only byte read; production preview/export paths consume URLs, not this helper. */
    internal fun readBytesForTests(path: String): ByteArray {
        val data: NSData = NSData.dataWithContentsOfFile(path)
            ?: error("IosSourceStager.readBytesForTests: unreadable '$path'")
        return IosByteArrayInterop.fromNSData(data)
    }

    /**
     * Best-effort unlink for app-owned temp sources (`ewm_src_*` / provisional).
     * Production call sites: progressive adopt rollback, leave-editor release, host dispose,
     * and supersede/cancel. OS temp scrub is only a last resort if a process is killed mid-import.
     */
    fun deleteQuietly(path: String) {
        if (path.isBlank()) return
        runCatching {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}
