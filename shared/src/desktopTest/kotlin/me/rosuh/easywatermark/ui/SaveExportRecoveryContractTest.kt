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

    /** I0 — shell exposes destination / policy / counts / outcome detail slots. */
    @Test
    fun i0_shellExposesDestinationPolicyCountsOutcome() {
        val shell = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportSheetShell.kt",
        )
        assertTrue(shell.contains("destinationLine"))
        assertTrue(shell.contains("filenamePolicyLine"))
        assertTrue(shell.contains("countsLine"))
        assertTrue(shell.contains("outcomeDetailLine"))
        assertTrue(shell.contains("sharedComposeExportDestination"))
        assertTrue(shell.contains("sharedComposeExportFilenamePolicy"))
        assertTrue(shell.contains("sharedComposeExportCounts"))
        assertTrue(shell.contains("sharedComposeExportOutcomeDetail"))
    }

    /** I0 — three hosts pass destination + filename policy into the shell. */
    @Test
    fun i0_hostsWireDestinationAndFilenamePolicy() {
        val android = read("app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt")
        val desktop = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        val ios = read("shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt")
        assertTrue(android.contains("dialog_save_destination_album"))
        assertTrue(android.contains("dialog_save_filename_policy_android"))
        assertTrue(android.contains("destinationLine") && android.contains("filenamePolicyLine"))
        assertTrue(desktop.contains("dialog_save_destination_folder") || desktop.contains("destinationLine"))
        assertTrue(desktop.contains("dialog_save_filename_policy_desktop"))
        assertTrue(ios.contains("dialog_save_destination_photos"))
        assertTrue(ios.contains("dialog_save_filename_policy_ios"))
    }

    /** I0 — export batch catch must not interpolate t.message into product status. */
    @Test
    fun i0_exportCatchBlocksDoNotSurfaceThrowableMessage() {
        val desktop = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        val ios = read("shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt")
        // After I0: export failure status uses dialog_save_error_generic, not t.message.
        val dBatch = desktop.indexOf("exportAndAwait(exportItems)")
        assertTrue(dBatch >= 0)
        val dWin = desktop.substring(dBatch, (dBatch + 2500).coerceAtMost(desktop.length))
        assertFalse(
            "Export failed: \${t.message}" in dWin ||
                Regex("""Export failed:\s*\$\{t\.message\}""").containsMatchIn(dWin),
            "Desktop batch catch must not use t.message",
        )
        assertTrue(
            "exportErrorGeneric" in dWin || "dialog_save_error_generic" in desktop,
            "Desktop export sheet should use localized generic export error",
        )
        val iBatch = ios.indexOf("exportAndAwait(images)")
        assertTrue(iBatch >= 0)
        val iWin = ios.substring(iBatch, (iBatch + 2500).coerceAtMost(ios.length))
        assertFalse(
            "Export failed: \${t.message}" in iWin,
            "iOS batch catch must not use t.message",
        )
        assertTrue(
            "exportErrorGeneric" in iWin || "dialog_save_error_generic" in ios,
        )
    }
}
