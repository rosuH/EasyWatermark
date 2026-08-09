package me.rosuh.easywatermark.session

import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosFinalRenderSpine
import me.rosuh.easywatermark.render.IosRenderRequest
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS [ExportPipelinePort] (C3): full-resolution Final Export through [IosFinalRenderSpine] →
 * [CommonWatermarkPipeline], honoring [UserPreferences] format/quality and per-item offset.
 *
 * Snapshots path/config/prefs/offset **before** source/temp IO. Does not apply a preview max-edge
 * cap. [MediaRef.value] is a readable filesystem path; returns temp path under [NSTemporaryDirectory].
 *
 * Issue 22 §2.5: atomic write of encoded bytes precedes [ImageInfo] width/height mutation.
 * D1: returns typed [ExportOutcome] with [ExportedMedia] (ref/dims/format/bytes).
 *
 * Optional internal [textFontFamilyProvider] construction seam for Text-mode only (tests).
 * Production defaults to [FontFamily.Default] (ADR-0025). Image mode never invokes the provider.
 */
/** J5: Session export port — not called from Swift. */
internal class IosExportPipelinePort internal constructor(
    private val textFontFamilyProvider: () -> FontFamily?,
) : ExportPipelinePort {

    /** Production entry: Text mode uses the system default face (ADR-0025). */
    constructor() : this({ FontFamily.Default })

    /**
     * Test-only atomic-write override so a failed write can be forced without message parsing
     * or real filesystem failure. Production leaves this null and uses Foundation `writeToFile`.
     */
    internal var atomicWriteOverrideForTests: ((bytes: ByteArray, path: String) -> Boolean)? = null

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome {
        return try {
            // Freeze identity before any filesystem IO (C3).
            val path = imageInfo.uri.value
            val request = IosRenderRequest(
                config = config,
                prefs = prefs,
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )
            if (path.isBlank()) {
                return ExportOutcome.failure(
                    ExportFailure.SourceDecode(message = "Empty image path"),
                )
            }
            val data: NSData = NSData.dataWithContentsOfFile(path)
                ?: return ExportOutcome.failure(
                    ExportFailure.SourceDecode(message = "Source not readable: $path"),
                )
            // H2: sequential scopes — drop source/icon byte arrays after encode before write
            // so peak is not source+icon+encoded+file simultaneously longer than needed.
            val encoded = run {
                val imageBytes = IosByteArrayInterop.fromNSData(data)
                val iconBytes: ByteArray? = if (config.markMode == WatermarkMode.Image) {
                    IosIconPersistence.readIconBytes(config.iconUri)
                } else {
                    null
                }
                // Text only: never call the provider in Image mode (C4.3 seam).
                val fontFamily = if (config.markMode == WatermarkMode.Text) {
                    textFontFamilyProvider()
                } else {
                    null
                }
                try {
                    IosFinalRenderSpine.renderAndEncode(
                        imageBytes = imageBytes,
                        request = request,
                        iconBytes = iconBytes,
                        fontFamily = fontFamily,
                    )
                } catch (e: Exception) {
                    return ExportOutcome.failure(
                        ExportFailure.Render(message = e.message ?: "iOS render failed"),
                    )
                }
                // imageBytes / iconBytes fall out of scope; only encoded retained for write.
            }
            val ext = encoded.format.fileExtension
            val outPath = NSTemporaryDirectory() + "ewm_out_" + NSUUID().UUIDString + "." + ext
            // Issue 22 §2.5 steps 7–8: write first; mutate dimensions only after success.
            val ok = writeEncodedBytes(encoded.bytes, outPath)
            if (!ok) {
                return ExportOutcome.failure(
                    ExportFailure.Persistence(message = "Failed to write $outPath"),
                )
            }
            imageInfo.width = encoded.width
            imageInfo.height = encoded.height
            ExportOutcome.success(
                ExportedMedia(
                    ref = MediaRef(outPath),
                    width = encoded.width,
                    height = encoded.height,
                    format = encoded.format,
                    byteCount = encoded.bytes.size.toLong(),
                ),
            )
        } catch (e: Exception) {
            ExportOutcome.failure(
                ExportFailure.Io(message = e.message ?: "iOS export failed"),
            )
        }
    }

    private fun writeEncodedBytes(bytes: ByteArray, path: String): Boolean {
        val override = atomicWriteOverrideForTests
        return if (override != null) {
            override(bytes, path)
        } else {
            IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        }
    }
}
