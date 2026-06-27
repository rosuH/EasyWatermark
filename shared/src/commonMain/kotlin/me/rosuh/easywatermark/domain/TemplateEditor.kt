package me.rosuh.easywatermark.domain

import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.TemplateRepository
import kotlin.time.Clock

/**
 * S4d-98: the platform-neutral template add/update/delete use-case, extracted from Android
 * `MainViewModel`. Wraps the commonMain [TemplateRepository]; **Android UI state stays in the VM**.
 *
 * [isDaoNull] is the small availability signal the VM uses to keep emitting `UiState.DatabaseError`
 * when the database is unavailable (the VM owns `UiState`, not this use-case). [add] constructs the
 * `Template` exactly as before (id `0`, supplied content, two separate `Clock.System.now()` calls for
 * creation/last-modified) and inserts it; [update]/[delete] are simple repo delegations.
 *
 * Sibling of [WatermarkConfigEditor] (S4d-96) and [OutputPrefsEditor] (S4d-97): suspend methods, no
 * `CoroutineScope` (the Android VM keeps `viewModelScope`/`launch`).
 */
class TemplateEditor(private val repo: TemplateRepository) {

    fun isDaoNull(): Boolean = repo.checkIfIsDaoNull()

    suspend fun add(content: String) {
        val template = Template(
            0,
            content = content,
            creationDate = Clock.System.now(),
            lastModifiedDate = Clock.System.now()
        )
        repo.insertTemplate(template)
    }

    suspend fun update(template: Template) {
        repo.updateTemplate(template)
    }

    suspend fun delete(template: Template) {
        repo.deleteTemplate(template)
    }
}
