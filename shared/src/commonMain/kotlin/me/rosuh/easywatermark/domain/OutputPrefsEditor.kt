package me.rosuh.easywatermark.domain

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.repo.UserConfigRepository

/**
 * Platform-neutral use-case for writing output format and compress level via [UserConfigRepository].
 *
 * Callers own coroutine scopes and any post-save UI side effects.
 */
class OutputPrefsEditor(private val repo: UserConfigRepository) {

    suspend fun save(format: ImageFormat, level: Int) {
        repo.updateFormat(format)
        repo.updateCompressLevel(level)
    }
}
