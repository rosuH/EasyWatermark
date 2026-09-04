package me.rosuh.easywatermark.domain

import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.TemplateRepository
import kotlin.time.Clock

/**
 * Platform-neutral template add/update/delete over [TemplateRepository].
 *
 * [isDaoNull] signals DB unavailability for UI. Methods are `suspend` without owning a scope.
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
