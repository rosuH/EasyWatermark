package me.rosuh.easywatermark.session

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

private const val DEFAULT_WATERMARK_TEXT = "EasyWatermark 水印"

/**
 * Single-process iOS graph (ADR-0017 Phase 4): **one** watermark DataStore + user prefs store,
 * shared by config bridges and [WatermarkSessionViewModel] (DataStore forbids dual stores per file).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "IosAppServices", exact = true)
class IosAppServices(
    val waterMarkRepo: WaterMarkRepository,
    val userConfigRepo: UserConfigRepository,
    val configBridge: IosWatermarkConfigBridge,
    val userConfigBridge: IosUserConfigBridge,
    val session: WatermarkSessionViewModel,
) {
    /**
     * Stage [imageBytes] to a temp file, run shared session export, return the output PNG path.
     * Swift-callable as `async throws`.
     */
    @Throws(Exception::class)
    suspend fun exportPickedImageBytes(imageBytes: ByteArray): String {
        require(imageBytes.isNotEmpty()) { "exportPickedImageBytes: empty image" }
        val srcPath = NSTemporaryDirectory() + "ewm_src_" + NSUUID().UUIDString
        val wrote = IosByteArrayInterop.toNSData(imageBytes).writeToFile(srcPath, atomically = true)
        check(wrote) { "exportPickedImageBytes: failed to stage source bytes" }
        val info = ImageInfo(MediaRef(srcPath))
        val wm = waterMarkRepo.waterMark.first()
        session.dispatchAndAwait(
            AppIntent.EnterEditor(
                selected = listOf(info),
                waterMark = wm,
            ),
        )
        session.exportAndAwait(listOf(info))
        return when (val st = info.jobState) {
            is JobState.Success -> {
                val ref = info.result?.data as? MediaRef
                    ?: error("export success without MediaRef")
                ref.value
            }
            is JobState.Failure -> {
                error(st.result.message ?: st.result.code ?: "export failed")
            }
            else -> error("export incomplete")
        }
    }
}

/**
 * Build the single iOS service graph. Call once per process and retain.
 */
fun defaultIosAppServices(): IosAppServices {
    val waterMarkRepo = WaterMarkRepository(
        dataStore = createWaterMarkDataStore(),
        defaultTextProvider = { DEFAULT_WATERMARK_TEXT },
        tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        logError = { message -> println("IosAppServices/WaterMarkRepository: $message") },
    )
    val userConfigRepo = UserConfigRepository(createUserConfigDataStore())
    val session = WatermarkSessionViewModel(
        waterMarkRepo = waterMarkRepo,
        userConfigRepo = userConfigRepo,
        exportPipeline = IosExportPipelinePort(),
    )
    return IosAppServices(
        waterMarkRepo = waterMarkRepo,
        userConfigRepo = userConfigRepo,
        configBridge = IosWatermarkConfigBridge(waterMarkRepo),
        userConfigBridge = IosUserConfigBridge(userConfigRepo),
        session = session,
    )
}
