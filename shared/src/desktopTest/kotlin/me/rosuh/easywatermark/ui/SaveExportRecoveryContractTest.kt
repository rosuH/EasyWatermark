package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D5 U1/U4 structural contracts for production host wiring (source-level, no device).
 */
class SaveExportRecoveryContractTest {

    private fun resolveRepoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found from user.dir=$cwd")
    }

    private fun read(path: String): String = resolveRepoFile(path).readText()

    /** U1 — three hosts wire Cancel to session.cancelExport (or viewModel cancelExport). */
    @Test
    fun u1_hostsWireCancelToSessionCancelExport() {
        val android = read("app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt")
        val desktop = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        val ios = read("shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt")
        val shell = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportSheetShell.kt",
        )

        assertTrue(
            shell.contains("sharedComposeExportCancel") && shell.contains("showCancelButton"),
            "shell must expose Cancel testTag",
        )
        assertTrue(
            android.contains("cancelExport()") && android.contains("onCancelClick"),
            "Android must wire onCancelClick → cancelExport",
        )
        assertTrue(
            desktop.contains("cancelExport()") && desktop.contains("onCancelClick"),
            "Desktop must wire onCancelClick → cancelExport",
        )
        assertTrue(
            ios.contains("cancelExport()") && ios.contains("onCancelClick"),
            "iOS must wire onCancelClick → cancelExport",
        )
    }

    /**
     * U4 — batch exportAndAwait path must not invent success via markExportFinished.
     * Fixture-only (empty selection) path may still call markExportFinished.
     */
    @Test
    fun u4_desktopBatchPathDoesNotCallMarkExportFinishedAfterExportAndAwait() {
        val desktop = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        // Locate the batch export block: exportAndAwait then status from Session counts.
        assertTrue(desktop.contains("session.exportAndAwait(exportItems)"))
        val batchIdx = desktop.indexOf("session.exportAndAwait(exportItems)")
        assertTrue(batchIdx >= 0)
        // Within a short window after exportAndAwait, must not call markExportFinished.
        val afterBatch = desktop.substring(batchIdx, (batchIdx + 800).coerceAtMost(desktop.length))
        assertFalse(
            afterBatch.contains("markExportFinished"),
            "batch path must not call markExportFinished immediately after exportAndAwait",
        )
        // Fixture path may still use markExportFinished with D5 comment.
        assertTrue(
            desktop.contains("markExportFinished") && desktop.contains("Fixture-only"),
            "fixture-only markExportFinished should remain documented",
        )
    }

    /** U2 structural — Retry failed wired on three hosts. */
    @Test
    fun u2_hostsWireRetryFailed() {
        val android = read("app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt")
        val desktop = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        val ios = read("shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt")
        val shell = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportSheetShell.kt",
        )
        assertTrue(shell.contains("sharedComposeExportRetryFailed"))
        assertTrue(android.contains("onRetryFailedClick") && android.contains("showRetryFailed"))
        assertTrue(desktop.contains("onRetryFailedClick") && desktop.contains("showRetryFailed"))
        assertTrue(ios.contains("onRetryFailedClick") && ios.contains("showRetryFailed"))
    }
}
