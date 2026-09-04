package me.rosuh.easywatermark.render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G1 fault matrix: crash-atomic publish is **old-or-new, never half-file**.
 *
 * Injects failures via [DesktopAtomicFileWrite.Hooks] (not OS power-loss lab).
 */
class DesktopAtomicFileWriteTest {

    private fun workDir(name: String): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "ewm-g1-atomic-${name}-${System.nanoTime()}",
        )
        check(dir.mkdirs() || dir.isDirectory)
        return dir
    }

    private fun orphanTemps(parent: File): List<File> =
        parent.listFiles()?.filter { it.name.startsWith(".ewm-") && it.name.endsWith(".tmp") }
            .orEmpty()

    @Test
    fun publish_success_writesExactBytes_andNoOrphanTemp() {
        val dir = workDir("ok")
        val target = File(dir, "out.bin")
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        DesktopAtomicFileWrite.publish(target, payload)
        assertTrue(target.isFile)
        assertContentEquals(payload, target.readBytes())
        assertTrue(orphanTemps(dir).isEmpty(), "temp must not remain after success")
    }

    @Test
    fun overwrite_failBeforeWrite_keepsOldBytes_cleansTemp() {
        val dir = workDir("before-write")
        val target = File(dir, "exact.bin")
        val old = byteArrayOf(9, 9, 9, 9)
        target.writeBytes(old)
        val boom = IllegalStateException("fail before write")
        val ex = assertFailsWith<IllegalStateException> {
            DesktopAtomicFileWrite.publish(
                target,
                byteArrayOf(1, 1, 1, 1),
                DesktopAtomicFileWrite.Hooks(beforeWrite = { throw boom }),
            )
        }
        assertEquals(boom.message, ex.message)
        assertContentEquals(old, target.readBytes(), "public target must stay old")
        assertTrue(orphanTemps(dir).isEmpty())
    }

    @Test
    fun overwrite_failAfterWriteBeforeSync_keepsOldBytes_cleansTemp() {
        val dir = workDir("after-write")
        val target = File(dir, "exact.bin")
        val old = byteArrayOf(7, 7, 7, 7, 7)
        target.writeBytes(old)
        assertFailsWith<IllegalStateException> {
            DesktopAtomicFileWrite.publish(
                target,
                byteArrayOf(2, 2, 2, 2, 2),
                DesktopAtomicFileWrite.Hooks(
                    afterWriteBeforeSync = { throw IllegalStateException("fail before sync") },
                ),
            )
        }
        assertContentEquals(old, target.readBytes())
        assertTrue(orphanTemps(dir).isEmpty())
    }

    @Test
    fun overwrite_failAfterSyncBeforeMove_keepsOldBytes_cleansTemp() {
        val dir = workDir("before-move")
        val target = File(dir, "exact.bin")
        val old = byteArrayOf(3, 3, 3)
        target.writeBytes(old)
        assertFailsWith<IllegalStateException> {
            DesktopAtomicFileWrite.publish(
                target,
                byteArrayOf(4, 4, 4, 4),
                DesktopAtomicFileWrite.Hooks(
                    afterSyncBeforeMove = { throw IllegalStateException("fail before move") },
                ),
            )
        }
        assertContentEquals(old, target.readBytes())
        assertTrue(orphanTemps(dir).isEmpty())
    }

    @Test
    fun overwrite_success_replacesWithNewBytesOnly() {
        val dir = workDir("replace-ok")
        val target = File(dir, "exact.bin")
        target.writeBytes(byteArrayOf(0, 0, 0, 0))
        val neu = byteArrayOf(5, 6, 7, 8, 9)
        DesktopAtomicFileWrite.publish(target, neu)
        assertContentEquals(neu, target.readBytes())
        assertTrue(orphanTemps(dir).isEmpty())
    }

    @Test
    fun uniqueExport_failBeforeMove_leavesNoPublicFinal_andNoOrphanTemp() {
        val dir = workDir("unique-fail")
        val final = File(dir, "watermarked_1.png")
        assertFalse(final.exists())
        assertFailsWith<IllegalStateException> {
            DesktopAtomicFileWrite.publish(
                final,
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                DesktopAtomicFileWrite.Hooks(
                    afterSyncBeforeMove = { throw IllegalStateException("fail before move") },
                ),
            )
        }
        assertFalse(final.exists(), "failed unique export must not create public final")
        assertTrue(orphanTemps(dir).isEmpty())
    }

    @Test
    fun spine_renderAndSave_honorsFaultHook_keepsPriorFile() {
        val dir = workDir("spine-hook")
        val target = File(dir, "out.jpg")
        val old = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        target.writeBytes(old)
        val prefs = me.rosuh.easywatermark.data.model.UserPreferences(
            me.rosuh.easywatermark.data.model.ImageFormat.JPEG,
            80,
        )
        val request = DesktopRenderRequest(
            config = me.rosuh.easywatermark.data.model.WaterMark.default,
            prefs = prefs,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        assertFailsWith<IllegalStateException> {
            DesktopRenderSaveSpine.renderAndSave(
                imageBytes = DesktopWatermarkComposer.sampleBackgroundPng(width = 32, height = 24),
                request = request,
                target = target,
                writeHooks = DesktopAtomicFileWrite.Hooks(
                    afterSyncBeforeMove = { throw IllegalStateException("spine fault") },
                ),
            )
        }
        assertContentEquals(old, target.readBytes())
        assertTrue(orphanTemps(dir).isEmpty())
    }
}
