package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result

/**
 * Typed single-item export outcome (Stage D / D1).
 *
 * Replaces bare [Result]<[MediaRef]> on [ExportPipelinePort.exportOne]. Success carries full
 * [ExportedMedia] facts; failure is a machine-readable [ExportFailure] taxonomy.
 */
sealed class ExportOutcome {
    data class Success(val media: ExportedMedia) : ExportOutcome()

    data class Failure(val failure: ExportFailure) : ExportOutcome()

    fun isSuccess(): Boolean = this is Success

    fun isFailure(): Boolean = this is Failure

    /** Legacy [Result] bridge for [me.rosuh.easywatermark.data.model.ImageInfo.result] until D5 host cutover. */
    fun toLegacyResult(): Result<MediaRef> = when (this) {
        is Success -> Result.success(media.ref)
        is Failure -> Result.failure(
            data = null,
            code = failure.legacyCode,
            message = failure.message,
        )
    }

    companion object {
        fun success(media: ExportedMedia): ExportOutcome = Success(media)

        fun failure(failure: ExportFailure): ExportOutcome = Failure(failure)
    }
}

/**
 * Typed export failure taxonomy (Stage D / D1). Distinguishes machine-readable kinds so hosts
 * need not parse messages or collapse everything to [ExportErrorCodes.FILE_NOT_FOUND].
 *
 * [legacyCode] preserves pre-D1 [Result.code] strings for the Session → ImageInfo bridge.
 */
sealed class ExportFailure {
    abstract val message: String?
    abstract val legacyCode: String

    /** Source missing, unreadable, empty path, or decode failed. */
    data class SourceDecode(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.FILE_NOT_FOUND,
    ) : ExportFailure()

    /** Watermark compose / raster failed (including invalid icon plan). */
    data class Render(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.RENDER,
    ) : ExportFailure()

    /** Bitmap/bytes encode failed. */
    data class Encode(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.ENCODE,
    ) : ExportFailure()

    /** Permission denied for read/write or photo library. */
    data class Permission(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.PERMISSION,
    ) : ExportFailure()

    /** Disk full, stream I/O, or OOM during export. */
    data class Io(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.IO,
    ) : ExportFailure() {
        companion object {
            fun outOfMemory(message: String? = null): Io =
                Io(message = message, legacyCode = ExportErrorCodes.SAVE_OOM)
        }
    }

    /** Provider / MediaStore / atomic write / publish failed after render. */
    data class Persistence(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.PERSISTENCE,
    ) : ExportFailure()

    /** Cooperative cancellation (D2 will rethrow [kotlinx.coroutines.CancellationException]). */
    data class Cancelled(
        override val message: String? = null,
        override val legacyCode: String = ExportErrorCodes.CANCELLED,
    ) : ExportFailure()
}
