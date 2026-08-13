package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.rosuh.easywatermark.ui.theme.previewCrossfadeDurationMs
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.render.PreviewResolutionPolicy

/**
 * Structural + budget diagnosis for filmstrip scroll / image switch at ~50 images.
 *
 * R1 (2026-08-12): raised WM budgets so focus+±2 at 1080 fits; still cannot hold all 50 at 720.
 * R3: host no longer warms FilmstripRepo — Coil ProductAsyncImage owns filmstrip UI.
 */
class FiftyImageFilmstripSwitchDiagnosisTest {

    @Test
    fun previewCrossfade_isHardCut_zeroMs() {
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Full, aspectDelta = 0f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Reduced, aspectDelta = 0.5f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Off, aspectDelta = 1f))
    }

    @Test
    fun watermarkedCache_cannotHoldFiftyPreviews_butNeighborWindowFitsAt1080() {
        val repoSrc = readFirst(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/render/IosPreviewImageRepository.kt",
            "src/iosMain/kotlin/me/rosuh/easywatermark/render/IosPreviewImageRepository.kt",
        )
        val entryCap = Regex(
            """DEFAULT_WATERMARKED_ENTRIES_MAX:\s*Int\s*=\s*(\d+)""",
        ).find(repoSrc)!!.groupValues[1].toInt()
        val byteCapMiB = Regex(
            """DEFAULT_WATERMARKED_BYTES_MAX:\s*Long\s*=\s*(\d+)L\s*\*\s*1024\s*\*\s*1024""",
        ).find(repoSrc)!!.groupValues[1].toLong()
        val jointMiB = Regex(
            """SOURCE_AND_PREVIEW_BYTES_MAX:\s*Long\s*=\s*(\d+)L\s*\*\s*1024\s*\*\s*1024""",
        ).find(repoSrc)!!.groupValues[1].toLong()
        val byteCap = byteCapMiB * 1024 * 1024
        val jointCap = jointMiB * 1024 * 1024
        // R1 production constants.
        assertEquals(48, entryCap)
        assertEquals(48L * 1024 * 1024, byteCap)
        assertEquals(64L * 1024 * 1024, jointCap)

        for (edge in listOf(
            PreviewResolutionPolicy.BUCKET_720,
            PreviewResolutionPolicy.BUCKET_1080,
            PreviewResolutionPolicy.BUCKET_1440,
            PreviewResolutionPolicy.BUCKET_1920,
        )) {
            val bytesPer = edge.toLong() * edge * 4
            val byBytes = (byteCap / bytesPer).toInt()
            val effective = minOf(entryCap, byBytes)
            assertTrue(
                effective < 50,
                "edge=$edge effective capacity $effective must be << 50 images",
            )
            if (edge == PreviewResolutionPolicy.BUCKET_720) {
                assertTrue(effective >= 20, "R1: ≥20×720 WM frames by bytes (got $effective)")
            }
            // R1: focus+±2 must fit at 1080 under both purpose and joint caps.
            if (edge == PreviewResolutionPolicy.BUCKET_1080) {
                assertTrue(
                    bytesPer * 5 <= byteCap,
                    "edge=$edge: focus+±2 must fit watermarkedBytesMax",
                )
                assertTrue(
                    bytesPer * 5 <= jointCap,
                    "edge=$edge: focus+±2 must fit joint budget",
                )
            }
        }
    }

    @Test
    fun iosHost_filmstripUiUsesCoil_andNoFilmstripRepoWarmPath() {
        val host = readIos("IosProductRootHost.kt")
        assertTrue(
            host.contains("ProductAsyncImage(") && host.contains("ProductThumb("),
            "editor filmstrip thumbs must use ProductAsyncImage/ProductThumb",
        )
        val produceCalls = Regex("""\bproduceState\s*[({]""").findAll(host).count()
        assertEquals(
            0,
            produceCalls,
            "produceState must not drive filmstrip UI after Coil migration (found $produceCalls)",
        )
        // R3: dead dual path removed.
        assertFalse(
            host.contains("prefetchFilmstripThumbs"),
            "host must not full-strip filmstrip-repo prefetch",
        )
        assertFalse(
            host.contains("ensureFocusFilmstripThumb"),
            "host must not warm repository Filmstrip purpose on focus",
        )
        assertFalse(
            host.contains("decodeFilmstripThumb"),
            "host must not ImageIO-decode into FilmstripRepo for UI",
        )
        assertFalse(
            host.contains("awaitLastFilmstripPrefetchForTests"),
            "test seam for dead prefetch must be gone",
        )
        assertTrue(
            host.contains("thumbnail = {") && host.contains("ProductAsyncImage"),
            "thumbnail lambda must be ProductAsyncImage (Coil)",
        )
        // Coil-only comment / R3 marker present for reviewers.
        assertTrue(
            host.contains("Coil-only") || host.contains("R3"),
            "host should document Coil-only filmstrip (R3)",
        )
    }

    @Test
    fun iosHost_switchPath_awaitsSelectAndRastersOnMiss_withNeighborOnlyPrefetch() {
        val host = readIos("IosProductRootHost.kt")
        assertTrue(host.contains("paintWatermarkedCacheHitIfPresent"))
        assertTrue(host.contains("AppIntent.SelectCurrent"))
        assertTrue(host.contains("renderPreviewForCurrentSelection"))
        assertTrue(host.contains("prefetchNeighborWatermarkedPreviews"))
        assertTrue(
            host.contains("listOf(-2, -1, 1, 2)"),
            "neighbor prefetch window must be ±2",
        )
        // Neighbor raster must hop Default — repository completion is host Main.
        val neighborFn = host.indexOf("private suspend fun warmNeighborWatermarkedPreviews")
        assertTrue(neighborFn >= 0, "missing warmNeighborWatermarkedPreviews")
        val neighborBody = host.substring(neighborFn, (neighborFn + 2200).coerceAtMost(host.length))
        assertTrue(
            neighborBody.contains("withContext(Dispatchers.Default)") &&
                neighborBody.contains("renderWatermarked"),
            "neighbor WM raster must withContext(Default) so filmstrip tap does not freeze Main",
        )
        assertTrue(
            host.contains("never full-strip") || host.contains("never full strip") ||
                host.contains("Neighbor WM prefetch"),
            "neighbor path must stay non-full-strip",
        )
        val fetcher = readIosImage("ProductThumbFetcher.ios.kt")
        assertTrue(fetcher.contains("IosImageIODecoder.decodeThumbnail"))
        assertTrue(fetcher.contains("toSkiaBitmapOrNull") || fetcher.contains("readPixels"))
        assertTrue(
            fetcher.contains("isSampled = false"),
            "isSampled=false required for Coil memory hits on LazyRow recycle",
        )
    }

    @Test
    fun filmstripScaffold_settlePublishesSelection_withSnapFling() {
        val strip = readCommonUi("EditorPhotoStrip.kt")
        assertTrue(strip.contains("rememberSnapFlingBehavior"))
        assertTrue(strip.contains("shouldPublishOnSettle"))
        assertTrue(strip.contains("onItemSelectedState.value(target)"))
        assertFalse(strip.contains("LazyLayoutCacheWindow"))
        assertFalse(strip.contains("beyondBoundsItemCount"))
    }

    private fun readIos(name: String): String = readFirst(
        "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/$name",
        "src/iosMain/kotlin/me/rosuh/easywatermark/ui/$name",
    )

    private fun readIosImage(name: String): String = readFirst(
        "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/image/$name",
        "src/iosMain/kotlin/me/rosuh/easywatermark/ui/image/$name",
    )

    private fun readCommonUi(name: String): String = readFirst(
        "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/$name",
        "src/commonMain/kotlin/me/rosuh/easywatermark/ui/$name",
    )

    private fun readFirst(vararg rel: String): String {
        val hit = rel.map { File(it) }.firstOrNull { it.isFile }
            ?: error("missing source among ${rel.toList()} cwd=${File(".").absolutePath}")
        return hit.readText()
    }
}
