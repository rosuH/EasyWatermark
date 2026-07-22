package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C4.4R.2 Desktop half — fail-closed **source wiring guard**.
 *
 * Structural evidence only: not a gesture/runtime proof. Geometry remains
 * [ClampPreviewOffsetDragTest]; Session commit-before-export remains
 * [me.rosuh.easywatermark.session.OffsetExportOrderingTest].
 */
class ClampPreviewOffsetHostWiringTest {

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

    /** Strip // line comments and /* block */ comments for executable-code counts. */
    private fun stripKotlinComments(source: String): String {
        val noBlock = source.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    @Test
    fun desktop_clamp_preview_offset_wiring_guard() {
        val window = resolveRepoFile(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        ).readText()
        val bridge = resolveRepoFile(
            "shared/src/desktopMain/kotlin/me/rosuh/easywatermark/ui/DesktopClampPreviewOffsetDrag.kt",
        ).readText()
        val renderRequestOwner = resolveRepoFile(
            "shared/src/desktopMain/kotlin/me/rosuh/easywatermark/render/DesktopRenderSaveSpine.kt",
        ).readText()

        // --- Bridge: public visibility + exactly one executable delegate ---
        val bridgeCode = stripKotlinComments(bridge)
        val publicDecl = Regex(
            """(?m)^(?!\s*(?:internal|private|protected)\b)\s*fun\s+Modifier\.desktopClampPreviewOffsetDrag\s*\(""",
        )
        assertTrue(
            publicDecl.containsMatchIn(bridgeCode),
            "bridge must declare public fun Modifier.desktopClampPreviewOffsetDrag(",
        )
        assertFalse(
            Regex("""(?m)^\s*(?:internal|private)\s+fun\s+Modifier\.desktopClampPreviewOffsetDrag\s*\(""")
                .containsMatchIn(bridgeCode),
            "bridge declaration must not be internal/private",
        )
        val delegateCount = Regex("""(?<!\.)\bclampPreviewOffsetDrag\s*\(""")
            .findAll(bridgeCode)
            .count()
        assertEquals(
            1,
            delegateCount,
            "bridge must contain exactly one executable clampPreviewOffsetDrag( delegate",
        )
        assertFalse("applyOffset" in bridgeCode, "bridge must not call Session")
        assertFalse("previewGeneration" in bridgeCode, "bridge must not own invalidation")
        assertFalse("computeFittedImageRect" in bridgeCode, "bridge must not own geometry")
        assertFalse("resolveClampDragCommit" in bridgeCode, "bridge must not re-implement resolver")
        assertFalse("detectDragGestures" in bridgeCode, "bridge must not own gestures")
        assertFalse("mutableStateOf" in bridgeCode, "bridge must not own Compose state")
        assertFalse(
            "WaterMarkRepository" in bridgeCode || "updateOffset" in bridgeCode,
            "bridge no repo",
        )

        // --- DesktopWindow: bridge use, no local geometry ---
        assertTrue(
            "desktopClampPreviewOffsetDrag" in window,
            "DesktopWindow must apply desktopClampPreviewOffsetDrag on preview",
        )
        assertFalse(
            Regex("""\.clampPreviewOffsetDrag\s*\(""").containsMatchIn(window),
            "DesktopWindow must not call internal clampPreviewOffsetDrag directly",
        )
        assertFalse("detectDragGestures" in window, "no host-local drag gestures")
        assertFalse("computeFittedImageRect" in window, "no host-local fitted math")
        assertFalse("applyClampDragDelta" in window, "no host-local delta math")

        // padding(12.dp) immediately before bridge so pointer coords match drawable bounds.
        assertTrue(
            Regex(
                """\.padding\s*\(\s*12\.dp\s*\)\s*\n\s*\.desktopClampPreviewOffsetDrag\s*\(""",
            ).containsMatchIn(window),
            "call chain must be padding(12.dp) then .desktopClampPreviewOffsetDrag(",
        )

        // Call-site (not import): require '('.
        val bridgeIdx = window.indexOf(".desktopClampPreviewOffsetDrag(")
        assertTrue(bridgeIdx >= 0, "bridge call site required on preview Modifier")
        val callbackSlice = window.substring(
            bridgeIdx,
            (bridgeIdx + 2200).coerceAtMost(window.length),
        )
        val callbackCode = stripKotlinComments(callbackSlice)

        // URI identity fail-closed near callback.
        assertTrue(
            Regex("""\.uri\s*==\s*dragUri|it\.uri\s*==\s*dragUri|uri\s*==\s*dragUri""")
                .containsMatchIn(callbackCode) ||
                (
                    "dragUri" in callbackCode &&
                        "curImageInfo" in callbackCode &&
                        Regex("""\.uri\s*==""").containsMatchIn(callbackCode)
                    ),
            "callback must require live curImageInfo.uri == drag identity",
        )
        assertTrue(
            "curImageInfo" in callbackCode,
            "callback must resolve live curImageInfo",
        )
        assertFalse(
            Regex("""selectedImageList\.firstOrNull\s*\(""").containsMatchIn(callbackCode),
            "callback must not fall through to another list URI",
        )
        assertFalse(
            Regex("""selectedSessionImage\s*\?\.takeIf""").containsMatchIn(callbackCode),
            "callback must not fall through to selectedSessionImage alternate URI",
        )

        // Exactly one applyOffset and one previewGeneration++ in order in the callback neighborhood.
        val applyMatches = Regex("""session\.applyOffset\s*\(""")
            .findAll(callbackCode)
            .toList()
        assertEquals(
            1,
            applyMatches.size,
            "callback neighborhood must contain exactly one session.applyOffset(",
        )
        val genMatches = Regex("""previewGeneration\s*\+\+""")
            .findAll(callbackCode)
            .toList()
        assertEquals(
            1,
            genMatches.size,
            "callback neighborhood must contain exactly one previewGeneration++",
        )
        assertTrue(
            applyMatches.first().range.first < genMatches.first().range.first,
            "applyOffset must precede previewGeneration++",
        )

        assertFalse(
            "repo.updateOffset" in window || "waterMarkRepo.updateOffset" in window,
            "must not bypass Session with repo offset writers",
        )
        assertFalse(
            Regex("""selectedSessionImage\s*=""").containsMatchIn(callbackCode),
            "callback must not assign selectedSessionImage",
        )
        assertFalse(
            Regex("""launch\s*\{[\s\S]{0,200}applyOffset""").containsMatchIn(callbackCode),
            "offset commit must not be fire-and-forget launch{}",
        )

        // DesktopRenderRequest owner: required offset params, no defaults.
        val spineCode = stripKotlinComments(renderRequestOwner)
        assertTrue(
            "data class DesktopRenderRequest" in spineCode,
            "DesktopRenderSaveSpine.kt must own DesktopRenderRequest",
        )
        // offsetX/offsetY present as constructor params without `= ...` defaults on those lines.
        val offsetParamLines = spineCode.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("val offsetX") || it.startsWith("val offsetY") }
            .toList()
        assertTrue(
            offsetParamLines.any { it.startsWith("val offsetX") },
            "DesktopRenderRequest must declare offsetX",
        )
        assertTrue(
            offsetParamLines.any { it.startsWith("val offsetY") },
            "DesktopRenderRequest must declare offsetY",
        )
        assertTrue(
            offsetParamLines.all { !it.contains("=") },
            "DesktopRenderRequest offsetX/offsetY must have no default values: $offsetParamLines",
        )
    }
}
