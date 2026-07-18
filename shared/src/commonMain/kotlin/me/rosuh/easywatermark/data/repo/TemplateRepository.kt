package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.model.entity.Template
import kotlin.coroutines.CoroutineContext

/**
 * Template CRUD over Room. Null DAO ⇒ empty list and no-op writes.
 *
 * [ioContext] is injected (`Dispatchers.IO` is not available on Native commonMain).
 */
class TemplateRepository(
    private val templateDao: TemplateDao?,
    private val ioContext: CoroutineContext,
) {

    fun checkIfIsDaoNull(): Boolean {
        return templateDao == null
    }

    fun getAllTemplate(): Flow<List<Template>> {
        return templateDao?.getAllTemplate() ?: flow {
            emit(listOf())
        }
    }

    suspend fun insertTemplate(template: Template) = withContext(ioContext) {
        templateDao?.insertTemplate(template)
    }

    suspend fun deleteTemplate(template: Template) = withContext(ioContext) {
        templateDao?.deleteTemplate(template)
    }

    suspend fun updateTemplate(template: Template) = withContext(ioContext) {
        templateDao?.updateTemplate(template)
    }
}
