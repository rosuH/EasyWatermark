@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
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

    /** Best-effort cleanup for tests; production leaves temp files to the OS temp policy. */
    fun deleteQuietly(path: String) {
        if (path.isBlank()) return
        runCatching {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}
