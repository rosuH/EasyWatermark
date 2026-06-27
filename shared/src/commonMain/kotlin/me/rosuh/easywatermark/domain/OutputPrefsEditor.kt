package me.rosuh.easywatermark.domain

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.repo.UserConfigRepository

/**
 * S4d-97: the platform-neutral output-preference write, extracted from Android `MainViewModel.saveOutput`.
 *
 * A single `suspend` [save] writes the output format then the compress level (same order as before)
 * through the commonMain [UserConfigRepository]. No validation/clamping is added; behavior is
 * unchanged. The caller (`MainViewModel`) keeps `viewModelScope`/`launch` and the post-launch
 * `resetJobStatus()`; this use-case owns no `CoroutineScope`.
 *
 * Sibling of [WatermarkConfigEditor] (S4d-96): a small shared editor use-case with an immediate
 * Android consumer, positioning the flow for future Desktop/iOS reuse.
 */
class OutputPrefsEditor(private val repo: UserConfigRepository) {

    suspend fun save(format: ImageFormat, level: Int) {
        repo.updateFormat(format)
        repo.updateCompressLevel(level)
    }
}
