package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.rosuh.easywatermark.ui.save.ExportSuccessIconMs

/**
 * Structural production-seam checks for Export waterfall + success-icon entrance.
 */
class SaveExportWaterfallContractTest {

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("$relative not found from user.dir=$cwd")
    }

    @Test
    fun previewBox_usesLazyVerticalStaggeredGrid_notLazyRow() {
        val src = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportPreviewBox.kt",
        )
        assertTrue(src.contains("LazyVerticalStaggeredGrid"), "must use lazy staggered grid")
        assertTrue(src.contains("StaggeredGridCells.FixedSize"), "fixed card-width columns")
        assertTrue(src.contains("ExportWaterfallCardWidth"), "card width token")
        assertTrue(src.contains("itemAspectRatio"), "optional aspect-ratio provider")
        assertTrue(src.contains("freezeExportAspectRatio"), "must freeze first-known ratio")
        assertTrue(src.contains("heightIn"), "bounded viewport height")
        assertFalse(src.contains("LazyRow"), "horizontal row must be gone")
        assertTrue(src.contains("sharedComposeExportWaterfall"), "testTag for waterfall")
        assertTrue(src.contains("contentType"), "stable contentType retained")
        assertFalse(
            src.contains("exportWaterfallColumnCount"),
            "unused columnCount helper must stay deleted",
        )
        // Implementation helpers must not be public (J5 framework surface).
        assertTrue(
            Regex("""internal\s+fun\s+exportCardAspectRatioOrNull""").containsMatchIn(src),
            "exportCardAspectRatioOrNull must be internal",
        )
        assertTrue(
            Regex("""internal\s+fun\s+freezeExportAspectRatio""").containsMatchIn(src),
            "freezeExportAspectRatio must be internal",
        )
    }

    @Test
    fun sheetShell_forwardsNullableAspectRatioProvider() {
        val shell = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportSheetShell.kt",
        )
        assertTrue(shell.contains("itemAspectRatio"))
        assertTrue(shell.contains("itemAspectRatio = itemAspectRatio"))
        assertTrue(
            shell.contains("(T) -> Float?"),
            "nullable provider so hosts can report unknown pre-export",
        )
    }

    @Test
    fun hosts_plumbStableSourceAspect_notImageInfoDefaultsAlone() {
        val android = read("app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt")
        val desktop = read(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        val ios = read(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt",
        )
        // Android: Coil onSuccess intrinsic size → thumbAspectByUri.
        assertTrue(android.contains("itemAspectRatio"))
        assertTrue(android.contains("thumbAspectByUri") || android.contains("onSuccess"))
        assertFalse(
            android.contains("exportCardAspectRatio(it.width, it.height)"),
            "Android must not use ImageInfo 1×1 defaults alone",
        )
        // Desktop: desktopThumbCache dims first.
        assertTrue(desktop.contains("itemAspectRatio"))
        assertTrue(desktop.contains("desktopThumbCache"))
        assertFalse(
            desktop.contains("exportCardAspectRatio(it.width, it.height)"),
            "Desktop must not use ImageInfo 1×1 defaults alone",
        )
        // iOS: export/filmstrip thumb dims first.
        assertTrue(ios.contains("itemAspectRatio"))
        assertTrue(
            ios.contains("exportThumbCache") && ios.contains("filmstripThumbCache"),
            "iOS must prefer decoded thumb caches",
        )
        assertFalse(
            ios.contains("exportCardAspectRatio(it.width, it.height)"),
            "iOS must not use ImageInfo 1×1 defaults alone",
        )
        assertTrue(
            ios.contains("exportAspectEpoch") && ios.contains("resolveExportItemAspectRatio"),
            "iOS must read snapshot epoch + production aspect seam",
        )
        assertTrue(
            ios.contains("noteExportAspectSourcesChanged"),
            "iOS must notify aspect observers on cache fills",
        )
    }

    @Test
    fun overlay_usesProductionDecisionHelper_andNearFinalIcon() {
        val src = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/ExportProgressOverlay.kt",
        )
        assertTrue(src.contains("resolveSuccessIconMotion"), "production must call decision helper")
        assertTrue(
            src.contains("when (resolveSuccessIconMotion(lastPhase, phase))"),
            "overlay must branch on resolveSuccessIconMotion",
        )
        assertTrue(src.contains("checkAlpha"), "must animate check alpha")
        assertTrue(src.contains("checkScale"), "must animate check scale")
        assertTrue(src.contains("ExportSuccessIconMs") ||
            src.contains("ExportSuccessIconMs=$ExportSuccessIconMs"))
        assertTrue(src.contains("LinearOutSlowInEasing"), "ease-out arrival")
        assertTrue(src.contains("0.94f") || src.contains("ExportSuccessIconStartScale"))
        assertTrue(src.contains("graphicsLayer"), "icon entrance via graphicsLayer")
        assertTrue(src.contains("iconMs <= 0"), "zero motion must snap icon")
        assertTrue(
            Regex("""internal\s+fun\s+resolveSuccessIconMotion""").containsMatchIn(src),
            "resolveSuccessIconMotion must be internal (J5)",
        )
        assertTrue(
            Regex("""internal\s+const\s+val\s+ExportSuccessIconMs""").containsMatchIn(src),
            "ExportSuccessIconMs must be internal (J5)",
        )
    }

    @Test
    fun iosHost_usesConstraintDrivenLoader_notHardcoded96() {
        val host = read(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt",
        )
        assertTrue(host.contains("IosExportThumbnailLoader.resolveMaxEdgePx"))
        assertTrue(host.contains("IosExportThumbnailLoader.isSufficient"))
        assertTrue(host.contains("IosExportThumbnailLoader.decodeFileOrNull"))
        assertTrue(host.contains("onSizeChanged"))
        val exportBlockStart = host.indexOf("IosExportThumbnailLoader.resolveMaxEdgePx")
        assertTrue(exportBlockStart >= 0)
        val exportBlock = host.substring(
            exportBlockStart,
            (exportBlockStart + 1200).coerceAtMost(host.length),
        )
        assertFalse(
            exportBlock.contains("maxEdgePx = 96"),
            "export sheet must not hard-code 96 after loader wiring",
        )
        val loader = read(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosExportThumbnailLoader.kt",
        )
        assertTrue(
            loader.contains("864"),
            "loader buckets must cover tall-card long edge 864",
        )
        assertTrue(
            Regex("""internal\s+object\s+IosExportThumbnailLoader""").containsMatchIn(loader),
            "loader must stay internal (J5)",
        )
    }
}
