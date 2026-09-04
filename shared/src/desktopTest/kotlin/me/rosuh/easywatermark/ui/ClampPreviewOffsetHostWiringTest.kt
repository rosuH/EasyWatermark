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
        val submitMatches = Regex("""paintConflator\.submit\s*\(""")
            .findAll(commitOnly)
            .toList()
        assertEquals(
            1,
            submitMatches.size,
            "onOffsetCommit must enqueue exactly one paintConflator.submit",
        )
        assertTrue(
            genMatches.first().range.first < submitMatches.first().range.first,
            "offsetPreviewGeneration++ must precede paintConflator.submit inside onOffsetCommit",
        )
        assertTrue(
            "DesktopPreviewPaint" in commitOnly,
            "commit paint must be a DesktopPreviewPaint",
        )
        assertTrue(
            Regex("""isDraft\s*=\s*false""").containsMatchIn(commitOnly),
            "commit paint must be non-draft",
        )
        assertFalse(
            "submitPreviewPaint" in commitOnly,
            "onOffsetCommit must not call submitPreviewPaint (that double-bumps gen)",
        )
        assertFalse(
            "overlayCell = null" in commitOnly || "previewPhoto = null" in commitOnly,
            "CLAMP commit must not clear live layers",
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

        // ADR-0033: dragModifier is built, then applied on LiveOverlayPreview (drag call is above).
        val callIdx = hostCode.indexOf(".clampPreviewOffsetDrag(")
        assertTrue(callIdx >= 0, "drag call site required")
        val previewStart = hostCode.indexOf("LiveOverlayPreview(", callIdx)
        assertTrue(
            previewStart > callIdx,
            "LiveOverlayPreview must consume the drag modifier after clampPreviewOffsetDrag(",
        )
        val imageSlice = hostCode.substring(
            callIdx,
            (previewStart + 1500).coerceAtMost(hostCode.length),
        )
        val enableSlice = hostCode.substring(
            (callIdx - 5000).coerceAtLeast(0),
            callIdx,
        )
        assertTrue("identityLive" in enableSlice, "CLAMP drag must require identityLive")
        assertTrue(
            "OverlayPreviewChrome.LiveLayers" in enableSlice,
            "CLAMP drag only when chrome is LiveLayers",
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
            Regex("""previewSourcePath\s*==\s*dragPath""").containsMatchIn(enableSlice),
            "enable must require previewSourcePath == dragPath",
        )
        assertTrue(
            Regex("""previewSourcePath\s*==\s*dragPath""")
                .containsMatchIn(enableSlice),
            "enable must still require previewSourcePath == dragPath",
        )
        assertTrue("LiveOverlayPreview(" in imageSlice, "drag modifier must feed LiveOverlayPreview")
        assertTrue(
            "withOffset" in callbackSlice,
            "CLAMP draft/commit must move overlay offset only",
        )
        assertTrue(
            Regex("""previewSourcePath\s*!=\s*dragPath|previewSourcePath\s*==\s*dragPath""")
                .containsMatchIn(callbackSlice),
            "callback must re-check previewSourcePath vs dragPath",
        )
        assertTrue("curImageInfo" in callbackSlice, "callback must read live curImageInfo")
        assertTrue(
            Regex("""\.uri\.value\s*==\s*dragPath|it\.uri\.value\s*==\s*dragPath""")
                .containsMatchIn(callbackSlice),
            "callback must require live uri.value == dragPath",
        )
        assertFalse(
            Regex("""selectedImageList\.firstOrNull\s*\(""").containsMatchIn(callbackSlice),
            "callback must not fall through to selectedImageList.firstOrNull",
        )
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
        assertTrue("onOffsetDraft" in callbackSlice, "iOS must wire onOffsetDraft for live UI draft")
        val iosGenMatches = Regex("""previewGen\s*\+\+""")
            .findAll(commitOnly)
            .toList()
        assertEquals(
            1,
            iosGenMatches.size,
            "onOffsetCommit must contain exactly one previewGen++",
        )
        assertTrue(
            applyMatches.first().range.first < iosGenMatches.first().range.first,
            "applyOffset must precede previewGen++ inside onOffsetCommit",
        )
        val commitFinishIdx = commitOnly.indexOf("commitBench.finish")
        assertTrue(commitFinishIdx >= 0, "commit bench finish required to bound the lambda")
        val renderIdx = commitOnly.indexOf("renderPreviewForCurrentSelection")
        assertTrue(
            renderIdx >= 0 && renderIdx < commitFinishIdx,
            "onOffsetCommit must enqueue renderPreviewForCurrentSelection before the bench finish",
        )
        val launchIdx = commitOnly.indexOf("hostScope.launch")
        assertTrue(
            launchIdx >= 0 && launchIdx < commitFinishIdx,
            "onOffsetCommit must launch the owned paint on hostScope",
        )
        assertFalse(
            "overlayCell = null" in commitOnly,
            "CLAMP commit must not clear the overlay layer",
        )
        assertFalse(
            "previewBitmap = null" in callbackSlice,
            "CLAMP drag must not drop the photo layer",
        )
        assertFalse(
            "repo.updateOffset" in host || "waterMarkRepo.updateOffset" in host,
            "must not bypass Session with repo offset writers",
        )
        assertFalse(
            Regex("""launch\s*\{[\s\S]{0,200}applyOffset""").containsMatchIn(callbackSlice),
            "applyOffset must not be fire-and-forget inside launch{}",
        )
        // Renderer ownership/budget policy not changed in this host (names must remain).
        assertTrue("IosPreviewRaster" in hostCode, "preview raster owner must remain")
        assertFalse(
            Regex("""IosFinalRenderSpine""").containsMatchIn(callbackSlice),
            "drag callback must not call final export spine",
        )
    }

    /**
     * Android CLAMP commit must bump [paintToken], enqueue one non-draft paint, and
     * drop any stale-token publish — not only drafts.
     */
    @Test
    fun android_clamp_commit_owns_paint_wiring_guard() {
        val screen = resolveRepoFile(
            "app/src/main/java/me/rosuh/easywatermark/ui/AndroidEditorScreen.kt",
        ).readText()
        val canvasStart = screen.indexOf("private fun WaterMarkCanvas(")
        assertTrue(canvasStart >= 0, "WaterMarkCanvas must exist")
        val canvasEnd = screen.indexOf("private data class ContentRect", canvasStart)
        val canvas = stripKotlinComments(
            screen.substring(
                canvasStart,
                if (canvasEnd > canvasStart) canvasEnd else screen.length,
            ),
        )

        assertFalse(
            Regex("""req\.token\s*!=\s*paintToken\s*&&\s*req\.isDraft""")
                .containsMatchIn(canvas),
            "stale-token drop must not be draft-only",
        )
        val staleDrops = Regex("""if\s*\(\s*req\.token\s*!=\s*paintToken\s*\)\s*return@paint""")
            .findAll(canvas)
            .count()
        assertTrue(
            staleDrops >= 2,
            "paintHandler must drop stale tokens at entry and before publishLiveLayers, found $staleDrops",
        )

        val helperIdx = canvas.indexOf("fun submitCommittedOverlayPaint")
        assertTrue(helperIdx >= 0, "WaterMarkCanvas must own submitCommittedOverlayPaint")
        val helper = canvas.substring(helperIdx, (helperIdx + 900).coerceAtMost(canvas.length))
        assertTrue("paintToken += 1" in helper || "paintToken +=1" in helper)
        assertTrue("paintConflator.submit" in helper)
        assertTrue(Regex("""isDraft\s*=\s*false""").containsMatchIn(helper))
        assertFalse("livePhoto = null" in helper)
        assertFalse("overlay = null" in helper)

        val dragEnd = canvas.indexOf("onDragEnd")
        assertTrue(dragEnd >= 0, "onDragEnd persist path required")
        val persist = canvas.substring(dragEnd, (dragEnd + 2500).coerceAtMost(canvas.length))
        val commitSubmitCount = Regex("""submitCommittedOverlayPaint\s*\(""")
            .findAll(persist)
            .count()
        assertEquals(
            2,
            commitSubmitCount,
            "both CLAMP persist paths (rubber-band and in-bounds) must enqueue one committed paint",
        )
        assertFalse("composeToBitmap" in persist)
        assertFalse("livePhoto = null" in persist)
        assertFalse("overlay = null" in persist)
    }
}
