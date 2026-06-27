package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.model.entity.Template
import kotlin.coroutines.CoroutineContext

/**
 * S4d-92: moved from `:app` to `:shared/commonMain` (same FQN). Behavior preserved: a null DAO (DB
 * unavailable) makes [getAllTemplate] emit an empty list and the write ops no-op.
 *
 * The one Android edge — writes ran on `Dispatchers.IO` — is now injected as [ioContext] because
 * `Dispatchers.IO` is not accessible in commonMain on the Native target. The Koin edge passes
 * `Dispatchers.IO`, so Android threading is byte-identical to before.
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
