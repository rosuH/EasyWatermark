package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C4.4R.2 Desktop + C4.4R.3 iOS — fail-closed **source wiring guards**.
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
        // Neighborhood must cover applyOffset + previewGeneration++ (H0.1 bench marks add lines).
        val callbackSlice = window.substring(
            bridgeIdx,
            (bridgeIdx + 3200).coerceAtMost(window.length),
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

        // Exactly one applyOffset; offset-preview invalidate on commit (draft may also bump gen).
        val applyMatches = Regex("""session\.applyOffset\s*\(""")
            .findAll(callbackCode)
            .toList()
        assertEquals(
            1,
            applyMatches.size,
            "callback neighborhood must contain exactly one session.applyOffset(",
        )
        val commitIdx = callbackCode.indexOf("onOffsetCommit")
        assertTrue(commitIdx >= 0, "onOffsetCommit required")
        val commitOnly = callbackCode.substring(commitIdx)
        val genMatches = Regex("""offsetPreviewGeneration\s*\+\+""")
            .findAll(commitOnly)
            .toList()
        assertEquals(
            1,
            genMatches.size,
            "onOffsetCommit must contain exactly one offsetPreviewGeneration++",
        )
        val applyInCommit = Regex("""session\.applyOffset\s*\(""")
            .findAll(commitOnly)
            .toList()
        assertEquals(1, applyInCommit.size)
        assertTrue(
            applyInCommit.first().range.first < genMatches.first().range.first,
            "applyOffset must precede offsetPreviewGeneration++ inside onOffsetCommit",
        )
        // Offset commit must not bump the debounced config-only generation.
        assertFalse(
            Regex("""(?<!offset)previewGeneration\s*\+\+""").containsMatchIn(commitOnly),
            "offset commit must not use debounced previewGeneration++",
        )
        assertTrue(
            "onOffsetDraft" in window,
            "DesktopWindow must wire onOffsetDraft for live UI draft",
        )
        assertFalse(
            "runSaveFlow" in commitOnly,
            "offset commit must not call runSaveFlow",
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

    /**
     * C4.4R.3 iOS half — structural guard for [IosProductRootHost] CLAMP drag wiring.
     * Same module as commonMain: calls internal [clampPreviewOffsetDrag] directly (no iOS bridge).
     */
    @Test
    fun ios_clamp_preview_offset_wiring_guard() {
        val host = resolveRepoFile(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt",
        ).readText()
        val hostCode = stripKotlinComments(host)

        // Exactly one production call to the shared internal modifier.
        val dragCallCount = Regex("""\.clampPreviewOffsetDrag\s*\(""")
            .findAll(hostCode)
            .count()
        assertEquals(
            1,
            dragCallCount,
            "IosProductRootHost must call .clampPreviewOffsetDrag( exactly once",
        )
        assertFalse("detectDragGestures" in hostCode, "no host-local drag gestures")
        assertFalse("computeFittedImageRect" in hostCode, "no host-local fitted math")
        assertFalse("applyClampDragDelta" in hostCode, "no host-local delta math")
        assertFalse(
            "desktopClampPreviewOffsetDrag" in hostCode,
            "iOS must not use the Desktop JVM bridge",
        )

        // Fail-closed: slice the single Image( invocation that owns the drag modifier.
        // No whole-file fallback for Fit / contentDescription strings.
        val callIdx = hostCode.indexOf(".clampPreviewOffsetDrag(")
        assertTrue(callIdx >= 0, "drag call site required")
        val imageStart = hostCode.lastIndexOf("Image(", callIdx)
        assertTrue(imageStart >= 0 && imageStart < callIdx, "drag must sit inside an Image(")
        // End at the matching Image close after the drag call (next top-level sibling is far;
        // use a bounded window from Image( through the drag callback).
        val imageSlice = hostCode.substring(
            imageStart,
            (callIdx + 4500).coerceAtMost(hostCode.length),
        )
        // Enable-gate lives just above the same Image (dragPath / watermarkedDisplayMatchesSelection).
        // Window ≥1100: EditorSelection ImageInfoUi projection at the EditorScreen call site
        // sits above the gate and pushed it past the old 900-char bound.
        val enableSlice = hostCode.substring(
            (imageStart - 5000).coerceAtLeast(0),
            callIdx,
        )
        // Require Image-local Fit + watermarked description (not elsewhere in the file).
        assertTrue(
            Regex("""contentDescription\s*=\s*"Watermarked preview"""").containsMatchIn(imageSlice),
            "same Image must set contentDescription = \"Watermarked preview\"",
        )
        assertTrue(
            Regex("""contentScale\s*=\s*ContentScale\.Fit""").containsMatchIn(imageSlice),
            "same Image must use contentScale = ContentScale.Fit",
        )
        assertTrue(
            Regex(
                """\.fillMaxSize\s*\(\s*\)\s*\n(?:\s*\.graphicsLayer\s*\{[\s\S]*?\}\s*\n)?\s*\.clampPreviewOffsetDrag\s*\(""",
            ).containsMatchIn(imageSlice),
            "same Image modifier chain must be fillMaxSize() then clampPreviewOffsetDrag(",
        )
        // Drag must not appear on a Crop thumbnail in this Image slice.
        assertFalse(
            Regex("""contentScale\s*=\s*ContentScale\.Crop""").containsMatchIn(imageSlice),
            "drag Image slice must not be a Crop thumbnail",
        )

        // H0.1-fix draft callbacks expand the neighborhood past the prior 4500 bound.
        val callbackSlice = hostCode.substring(
            callIdx,
            (callIdx + 7000).coerceAtMost(hostCode.length),
        )

        // Path + watermarked-display identity (not path alone — placeholders share previewSourcePath).
        // Progressive rebuild: Host uses watermarkedPreviewSourcePath + single-flight repository
        // instead of a raw wmPreviewCache map.
        assertTrue(
            Regex("""watermarkedPreviewSourcePath\s*==\s*dragPath""")
                .containsMatchIn(enableSlice) ||
                Regex("""wmPreviewCache\s*\[\s*dragPath\s*\]\s*===\s*displayPreview""")
                    .containsMatchIn(enableSlice),
            "enable must require watermarked display identity " +
                "(watermarkedPreviewSourcePath == dragPath or legacy wmPreviewCache match)",
        )
        assertTrue(
            Regex("""previewSourcePath\s*==\s*dragPath""")
                .containsMatchIn(enableSlice),
            "enable must still require previewSourcePath == dragPath",
        )
        assertTrue(
            "watermarkedDisplayMatchesSelection" in enableSlice ||
                Regex("""watermarkedPreviewSourcePath\s*==\s*dragPath""")
                    .containsMatchIn(enableSlice),
            "enable path must name watermarked-display identity gate",
        )
        assertTrue(
            "watermarkedDisplayMatchesSelection" in imageSlice ||
                Regex("""enabled\s*=[\s\S]{0,200}watermarkedDisplayMatchesSelection""")
                    .containsMatchIn(imageSlice),
            "enabled= must use watermarkedDisplayMatchesSelection on this Image",
        )
        // Callback triple identity + watermarked identity re-check.
        assertTrue(
            Regex("""previewSourcePath\s*!=\s*dragPath|previewSourcePath\s*==\s*dragPath""")
                .containsMatchIn(callbackSlice),
            "callback must re-check previewSourcePath vs dragPath",
        )
        assertTrue(
            Regex(
                """watermarkedPreviewSourcePath\s*!=\s*dragPath|""" +
                    """watermarkedPreviewSourcePath\s*==\s*dragPath|""" +
                    """wmPreviewCache\s*\[\s*dragPath\s*\]""",
            ).containsMatchIn(callbackSlice),
            "callback must re-check watermarkedPreviewSourcePath vs dragPath",
        )
        assertTrue(
            "curImageInfo" in callbackSlice,
            "callback must read live curImageInfo",
        )
        assertTrue(
            Regex(
                """\.uri\.value\s*==\s*dragPath|it\.uri\.value\s*==\s*dragPath""",
            ).containsMatchIn(callbackSlice),
            "callback must require live uri.value == dragPath",
        )
        assertFalse(
            Regex("""selectedImageList\.firstOrNull\s*\(""").containsMatchIn(callbackSlice),
            "callback must not fall through to selectedImageList.firstOrNull",
        )

        // Order inside onOffsetCommit: applyOffset → selected-key cache invalidate → previewGen bump.
        // H0.1-fix: onOffsetDraft may also bump previewGen for live paint.
        val commitIdx = callbackSlice.indexOf("onOffsetCommit")
        assertTrue(commitIdx >= 0, "onOffsetCommit required")
        val commitOnly = callbackSlice.substring(commitIdx)
        val applyMatches = Regex("""services\.session\.applyOffset\s*\(""")
            .findAll(commitOnly)
            .toList()
        assertEquals(
            1,
            applyMatches.size,
            "onOffsetCommit must contain exactly one services.session.applyOffset(",
        )
        val removeMatches = Regex(
            """previewImages\.invalidate(?:OwnedPath)?FromOwner\s*\(|wmPreviewCache\.remove\s*\(\s*dragPath\s*\)""",
        ).findAll(commitOnly).toList()
        assertEquals(
            1,
            removeMatches.size,
            "onOffsetCommit must invalidate exactly the selected watermarked key once",
        )
        val genMatches = Regex("""previewGen\s*(\+\+|=\s*previewGen\s*\+\s*1)""")
            .findAll(commitOnly)
            .toList()
        assertEquals(
            1,
            genMatches.size,
            "onOffsetCommit must bump previewGen exactly once",
        )
        assertTrue(
            applyMatches.first().range.first < removeMatches.first().range.first &&
                removeMatches.first().range.first < genMatches.first().range.first,
            "order must be applyOffset → watermarked-key invalidate → previewGen bump",
        )
        assertTrue(
            "onOffsetDraft" in callbackSlice,
            "iOS must wire onOffsetDraft for live UI draft",
        )
        assertFalse(
            "wmPreviewCache.clear()" in callbackSlice || "previewImages.clear()" in callbackSlice,
            "callback must not clear the whole watermarked preview cache",
        )
        assertFalse(
            "repo.updateOffset" in host || "waterMarkRepo.updateOffset" in host,
            "must not bypass Session with repo offset writers",
        )
        assertFalse(
            Regex("""launch\s*\{[\s\S]{0,200}applyOffset""").containsMatchIn(callbackSlice),
            "applyOffset must not be fire-and-forget inside launch{}",
        )
        // Existing preview rerender path after generation capture (within Image callback slice).
        assertTrue(
            "renderPreviewForCurrentSelection" in callbackSlice,
            "callback neighborhood must invoke existing renderPreviewForCurrentSelection",
        )
        assertTrue(
            Regex(
                """renderPreviewForCurrentSelection\s*\([\s\S]{0,120}gen\s*=""",
            ).containsMatchIn(callbackSlice),
            "rerender must pass captured gen=",
        )
        // Renderer ownership/budget policy not changed in this host (names must remain).
        assertTrue("IosPreviewRaster" in hostCode, "preview raster owner must remain")
        assertFalse(
            Regex("""IosFinalRenderSpine""").containsMatchIn(callbackSlice),
            "drag callback must not call final export spine",
        )
    }
}
