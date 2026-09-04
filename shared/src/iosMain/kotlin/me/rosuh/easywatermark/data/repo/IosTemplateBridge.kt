package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.domain.TemplateEditor

/**
 * A tiny Swift-friendly value view of a template row — just the `id` (for delete) and the * `content` (to display/apply). The Room `@Entity` [me.rosuh.easywatermark.data.model.entity.Template]
 * (which carries `kotlin.time.Instant` dates and a nullable content) does NOT cross to Swift; this flat
 * value does. Null content is normalized to the empty string.
 */
data class IosTemplate(val id: Int, val content: String)

/**
 * The iOS Swift-facing bridge for the common templates store, mirroring [IosUserConfigBridge]. *
 * Swift never touches the Kotlin `Flow`: [currentTemplates] is a **one-shot snapshot** (it collects
 * `repo.getAllTemplate().first()`), and the writes are plain `suspend` functions that the Kotlin/Native
 * Swift importer bridges to Swift `async` (a failure surfaces to the Swift `catch`). Only [IosTemplate]
 * (a flat value) and `Int`/`String` cross to Swift — no `Flow`, no `Room` entity, no `DataStore`.
 *
 * The bridge owns ONLY template-DB operations; it does NOT touch the watermark config. Applying a template
 * to the live watermark text is the Swift workflow's job (it calls its existing watermark-text setter),
 * so this bridge never opens a second watermark DataStore / duplicates [IosWatermarkConfigBridge].
 *
 * Single-instance-per-file: the templates Room DB is one file; a real iOS app retains ONE bridge (held in
 * `WatermarkWorkflow.swift`), exactly as it retains one [IosWatermarkConfigBridge] / [IosUserConfigBridge].
 */
class IosTemplateBridge(private val repo: TemplateRepository) {

    private val editor = TemplateEditor(repo)

    /** One-shot snapshot of the persisted templates as flat values (no `Flow` exposed to Swift). */
    suspend fun currentTemplates(): List<IosTemplate> =
        repo.getAllTemplate().first().map { IosTemplate(it.id, it.content ?: "") }

    /** Insert a new template with [content]. Suspends; a write failure surfaces as a Swift `async` error. */
    suspend fun addTemplate(content: String) {
        editor.add(content)
    }

    /**
 * Delete the template with [id]. Resolves the row from the current snapshot (the editor's delete takes
 * The entity), so Swift only passes an `Int`. A missing id is a no-op. Suspends; failures surface to Swift.     */
    suspend fun deleteTemplate(id: Int) {
        repo.getAllTemplate().first().firstOrNull { it.id == id }?.let { editor.delete(it) }
    }
}

/**
 * Build an [IosTemplateBridge] over the app's default **seeded** iOS templates DB — the no-arg
 * [buildTemplateDatabase] (: `NSDocumentDirectory` store + the bundled Android seed DB on first
 * Creation). A real iOS app calls this ONCE and retains the result (single-instance-per-file). * `Dispatchers.Default` is used for the repo query context because `Dispatchers.IO` is internal on the
 * Native target.
 */
fun defaultIosTemplateBridge(): IosTemplateBridge =
    IosTemplateBridge(TemplateRepository(buildTemplateDatabase().templateDao(), Dispatchers.Default))
