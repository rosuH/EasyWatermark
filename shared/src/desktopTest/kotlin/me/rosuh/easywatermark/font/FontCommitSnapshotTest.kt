package me.rosuh.easywatermark.font

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.CommonWatermarkPipeline
import me.rosuh.easywatermark.render.DesktopWatermarkTextRenderer
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontCommitSnapshotTest {

    @Test
    fun applyConfigIf_returns_full_datastore_snapshot_not_patched_launch_state() = runBlocking {
        val dir = File("build/tmp-font-snapshot-${System.nanoTime()}").apply { mkdirs() }
        try {
            val waterRepo = WaterMarkRepository(
                dataStore = createWaterMarkDataStore(dir),
                defaultTextProvider = { "EasyWatermark" },
                tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
                logError = {},
            )
            val session = WatermarkSessionViewModel(
                waterMarkRepo = waterRepo,
                userConfigRepo = UserConfigRepository(createUserConfigDataStore(dir)),
            )
            val access = DesktopWatermarkFontAccess(root = File(dir, "fonts").apply { mkdirs() })
            val system = access.listSystemFonts()
            val candidate = system.firstOrNull {
                val name = it.displayName.lowercase()
                name.contains("times") || name.contains("courier") || name.contains("menlo") ||
                    name.contains("monaco") || name.contains("serif")
            } ?: system.first()
            val resolvedB = access.resolve(candidate.ref)
            assertIs<FontResolution.Success>(resolvedB)

            waterRepo.updateText("HelloCommitted")
            waterRepo.updateColor(0xFF00AA00.toInt())
            waterRepo.updateTypeFace(TextTypeface.Bold)
            val launchBefore = session.launchScreenUiStateFlow.value.waterMark

            val committed = session.applyConfigIf(
                stillValid = { true },
                change = WatermarkConfigChange.FontSelection(
                    candidate.ref,
                    setOf(TextTypeface.Normal),
                ),
            )
            requireNotNull(committed)
            assertEquals("HelloCommitted", committed.text)
            assertEquals(0xFF00AA00.toInt(), committed.textColor)
            assertEquals(candidate.ref, committed.fontRef)
            assertEquals(TextTypeface.Normal, committed.textTypeface)
            if (launchBefore.text != "HelloCommitted") {
                assertNotEquals(launchBefore.text, committed.text)
            }
            assertEquals(committed, session.launchScreenUiStateFlow.value.waterMark)
            assertEquals(committed, waterRepo.currentConfig())

            val env = DesktopWatermarkTextRenderer.textRasterEnv()
            val preview = CommonWatermarkPipeline.composeCell(
                imageWidth = 360,
                config = committed,
                env = env,
                fontFamily = resolvedB.family,
            )
            val export = CommonWatermarkPipeline.composeCell(
                imageWidth = 360,
                config = waterRepo.currentConfig(),
                env = env,
                fontFamily = resolvedB.family,
            )
            assertEquals(preview.width, export.width)
            assertEquals(preview.height, export.height)
            assertEquals(
                preview.asSkiaBitmap().readPixels()?.contentHashCode(),
                export.asSkiaBitmap().readPixels()?.contentHashCode(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persist_failure_and_stale_generation_do_not_paint() = runBlocking {
        val dir = File("build/tmp-font-snapshot-fail-${System.nanoTime()}").apply { mkdirs() }
        try {
            val waterRepo = WaterMarkRepository(
                dataStore = createWaterMarkDataStore(dir),
                defaultTextProvider = { "EasyWatermark" },
                tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
                logError = {},
            )
            val session = WatermarkSessionViewModel(
                waterMarkRepo = waterRepo,
                userConfigRepo = UserConfigRepository(createUserConfigDataStore(dir)),
            )
            val skipped = session.applyConfigIf(
                stillValid = { false },
                change = WatermarkConfigChange.FontSelection(
                    WatermarkFontRef.System("desktop", "Courier"),
                    FontStyleCapability.All.styles,
                ),
            )
            assertNull(skipped)
            assertEquals(WatermarkFontRef.Default, session.launchScreenUiStateFlow.value.waterMark.fontRef)

            var gen = 1
            val request = gen
            gen = 2
            val committed = session.applyConfigIf(
                stillValid = { true },
                change = WatermarkConfigChange.Text("Later"),
            )
            requireNotNull(committed)
            val toPaint = if (request == gen) committed else null
            assertNull(toPaint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun collector_waiting_outside_lock_cannot_publish_stale_snapshot_after_font_commit() = runBlocking {
        val dir = File("build/tmp-font-sync-race-${System.nanoTime()}").apply { mkdirs() }
        try {
            val waterRepo = WaterMarkRepository(
                dataStore = createWaterMarkDataStore(dir),
                defaultTextProvider = { "EasyWatermark" },
                tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
                logError = {},
            )
            val session = WatermarkSessionViewModel(
                waterMarkRepo = waterRepo,
                userConfigRepo = UserConfigRepository(createUserConfigDataStore(dir)),
            )
            withTimeout(5_000) {
                while (session.launchScreenUiStateFlow.value.waterMark.text.isBlank()) {
                    yield()
                }
            }
            val access = DesktopWatermarkFontAccess(root = File(dir, "fonts").apply { mkdirs() })
            val candidate = access.listSystemFonts().firstOrNull {
                val name = it.displayName.lowercase()
                name.contains("times") || name.contains("courier") || name.contains("menlo") ||
                    name.contains("monaco") || name.contains("serif")
            } ?: access.listSystemFonts().first()

            val hold = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val captured = AtomicReference<me.rosuh.easywatermark.data.model.WaterMark?>(null)
            session.beforeWaterMarkSyncLock = {
                captured.set(waterRepo.currentConfig())
                entered.complete(Unit)
                hold.await()
            }
            waterRepo.updateText("StaleA")
            withTimeout(5_000) { entered.await() }
            val stale = requireNotNull(captured.get())
            assertEquals("StaleA", stale.text)
            assertEquals(WatermarkFontRef.Default, stale.fontRef)

            val seenFonts = CopyOnWriteArrayList<WatermarkFontRef>()
            val seenJob = launch {
                session.launchScreenUiStateFlow.collect { seenFonts.add(it.waterMark.fontRef) }
            }

            val committed = session.applyConfigIf(
                stillValid = { true },
                change = WatermarkConfigChange.FontSelection(
                    candidate.ref,
                    FontStyleCapability.All.styles,
                ),
            )
            requireNotNull(committed)
            assertEquals(candidate.ref, committed.fontRef)
            assertEquals("StaleA", committed.text)
            assertEquals(candidate.ref, session.launchScreenUiStateFlow.value.waterMark.fontRef)

            hold.complete(Unit)
            repeat(40) { yield() }
            delay(80)
            val finalLaunch = session.launchScreenUiStateFlow.value.waterMark
            assertEquals(candidate.ref, finalLaunch.fontRef)
            assertEquals("StaleA", finalLaunch.text)
            assertEquals(finalLaunch, waterRepo.currentConfig())
            val firstB = seenFonts.indexOfFirst { it == candidate.ref }
            assertTrue(firstB >= 0, seenFonts.toString())
            assertTrue(
                seenFonts.drop(firstB).all { it == candidate.ref },
                "collector must not republish A after B: $seenFonts",
            )
            seenJob.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }
}
