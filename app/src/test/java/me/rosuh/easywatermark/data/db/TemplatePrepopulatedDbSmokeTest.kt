package me.rosuh.easywatermark.data.db

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * S4d-92 prepopulated-DB smoke (driver decision gate).
 *
 * Proves the commonMain [AppDatabase], created through the production Android builder
 * [buildTemplateDatabase], still opens the locale-selected prepackaged seed assets
 * (`ewm-db-ch.db` / `ewm-db-eng.db`) and reads seeded templates back as non-empty — in Room
 * **compatibility mode** (no `SQLiteDriver` set), i.e. the framework SupportSQLite open-helper, the
 * same engine production used before this move. This is the evidence backing the "no explicit driver"
 * decision (no `sqlite-bundled`/`sqlite-framework` native payload). No device required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TemplatePrepopulatedDbSmokeTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        context.getDatabasePath("ewm-db").delete()
    }

    private fun openSeededDb(locale: Locale): AppDatabase {
        Locale.setDefault(locale)
        // Force createFromAsset to re-copy the locale-selected seed for this run.
        context.getDatabasePath("ewm-db").delete()
        return buildTemplateDatabase(context)
    }

    @Test
    fun chineseLocaleOpensSeededTemplates() {
        val db = openSeededDb(Locale.SIMPLIFIED_CHINESE)
        try {
            val templates = runBlocking { db.templateDao().getAllTemplate().first() }
            assertTrue("zh seed (ewm-db-ch.db) should expose seeded templates", templates.isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun englishLocaleOpensSeededTemplates() {
        val db = openSeededDb(Locale.ENGLISH)
        try {
            val templates = runBlocking { db.templateDao().getAllTemplate().first() }
            assertTrue("en seed (ewm-db-eng.db) should expose seeded templates", templates.isNotEmpty())
        } finally {
            db.close()
        }
    }
}
