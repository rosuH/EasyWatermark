package me.rosuh.easywatermark.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.TemplateRepository
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S4d-98: pins the non-suspend availability gate that drives `MainViewModel.addTemplate`'s
 * `UiState.DatabaseError` branch: a null DAO -> `isDaoNull() == true`, a present DAO -> `false`.
 *
 * Only `isDaoNull()` is covered: commonTest has no coroutine runner (`kotlinx-coroutines-test` is not
 * a dependency, and adding it is a forbidden Gradle change), so the suspend `add`/`update`/`delete`
 * are left to compile/link + the strict goldens. `TemplateRepository` accepts a nullable `TemplateDao`
 * and `TemplateDao` is an interface, so this fake is cheap with existing APIs (no new dependency).
 */
class TemplateEditorTest {

    private class FakeTemplateDao : TemplateDao {
        override fun getAllTemplate(): Flow<List<Template>> = flowOf(emptyList())
        override suspend fun insertTemplate(template: Template) {}
        override suspend fun deleteTemplate(template: Template) {}
        override suspend fun updateTemplate(template: Template) {}
    }

    @Test
    fun isDaoNull_true_when_dao_absent() {
        val editor = TemplateEditor(TemplateRepository(null, EmptyCoroutineContext))
        assertTrue(editor.isDaoNull())
    }

    @Test
    fun isDaoNull_false_when_dao_present() {
        val editor = TemplateEditor(TemplateRepository(FakeTemplateDao(), EmptyCoroutineContext))
        assertFalse(editor.isDaoNull())
    }
}
