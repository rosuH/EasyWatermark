package me.rosuh.easywatermark.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Constraint-driven Export thumbnail target + resolution-aware cache policy.
 */
class IosExportThumbnailLoaderTest {

    private fun entry(w: Int, h: Int, requested: Int): IosExportThumbnailLoader.Entry =
        IosExportThumbnailLoader.Entry(
            bitmap = ImageBitmap(w, h, ImageBitmapConfig.Argb8888),
            requestedMaxEdgePx = requested,
        )

    @Test
    fun resolve_measured216_picksAtLeast216Bucket() {
        // 72dp @ 3x = 216 physical px — legacy hard-coded 96 fails this.
        val target = IosExportThumbnailLoader.resolveMaxEdgePx(216, 216)
        assertTrue(target >= 216, "target=$target must cover 216px card")
        assertTrue(target in IosExportThumbnailLoader.Buckets.toList())
        assertEquals(216, target)
    }

    @Test
    fun resolve_tallCard1to4_covers864LayoutNeed() {
        // 72dp-wide × 1:4-tall @ 3x ≈ 216×864 — must not undersize below layout long edge.
        val target = IosExportThumbnailLoader.resolveMaxEdgePx(216, 864)
        assertTrue(target >= 864, "target=$target must cover 864px tall card long edge")
        assertEquals(IosExportThumbnailLoader.MaxBucketPx, target)
        assertTrue(
            IosExportThumbnailLoader.MaxBucketPx >= 864,
            "MaxBucketPx must cover max layout long edge",
        )
    }

    @Test
    fun resolve_portrait1to2_exceedsLegacy384() {
        // 72dp × 2 height @ 3x ≈ 216×432 — legacy 384 cap undersized this.
        val target = IosExportThumbnailLoader.resolveMaxEdgePx(216, 432)
        assertTrue(target >= 432, "target=$target must cover 432px need (legacy 384 failed)")
        assertEquals(576, target)
    }

    @Test
    fun resolve_zeroMeasure_isZero() {
        assertEquals(0, IosExportThumbnailLoader.resolveMaxEdgePx(0, 0))
        assertEquals(0, IosExportThumbnailLoader.resolveMaxEdgePx(-1, 100))
    }

    @Test
    fun resolve_usesLongEdgeAndCapsAtMaxBucket() {
        assertEquals(144, IosExportThumbnailLoader.resolveMaxEdgePx(100, 140))
        assertEquals(
            IosExportThumbnailLoader.MaxBucketPx,
            IosExportThumbnailLoader.resolveMaxEdgePx(2000, 2000),
        )
    }

    @Test
    fun isSufficient_undersizedPathEntry_mustUpgrade() {
        val small = entry(w = 96, h = 96, requested = 96)
        assertFalse(IosExportThumbnailLoader.isSufficient(small, neededMaxEdgePx = 216))
        assertFalse(IosExportThumbnailLoader.isSufficient(null, neededMaxEdgePx = 216))
    }

    @Test
    fun isSufficient_sameOrLargerRequest_reuses() {
        val hit = entry(w = 216, h = 216, requested = 216)
        assertTrue(IosExportThumbnailLoader.isSufficient(hit, neededMaxEdgePx = 216))
        assertTrue(IosExportThumbnailLoader.isSufficient(hit, neededMaxEdgePx = 144))
        // Higher request still covers lower need via bitmap edge.
        val bigger = entry(w = 288, h = 200, requested = 288)
        assertTrue(IosExportThumbnailLoader.isSufficient(bigger, neededMaxEdgePx = 216))
    }

    @Test
    fun isSufficient_naturallySmallSource_doesNotUpgradeForever() {
        // Source decoded at 216 request but natural long edge is 80 → sufficient forever.
        val natural = entry(w = 80, h = 60, requested = 216)
        assertTrue(IosExportThumbnailLoader.isSufficient(natural, neededMaxEdgePx = 216))
        assertTrue(IosExportThumbnailLoader.isSufficient(natural, neededMaxEdgePx = 864))
    }

    @Test
    fun enforce_keepsEntryAndByteBudgets() {
        val map = linkedMapOf<String, IosExportThumbnailLoader.Entry>()
        repeat(6) { i -> map["e$i"] = entry(40, 40, requested = 96) }
        val removed = IosExportThumbnailLoader.enforce(
            map,
            maxEntries = 3,
            maxBytes = Long.MAX_VALUE,
        )
        assertEquals(3, removed)
        assertEquals(3, map.size)

        val heavy = linkedMapOf<String, IosExportThumbnailLoader.Entry>()
        repeat(4) { i -> heavy["h$i"] = entry(100, 100, requested = 96) }
        val per = IosExportThumbnailLoader.approxBytes(heavy.values.first())
        IosExportThumbnailLoader.enforce(
            heavy,
            maxEntries = 10,
            maxBytes = per * 2 + 1,
        )
        assertTrue(heavy.size <= 2)
        assertTrue(IosExportThumbnailLoader.totalApproxBytes(heavy) <= per * 2 + 1)
    }

    @Test
    fun hostConstants_exportBudgetsUnchanged() {
        assertEquals(48, IosProductRootHost.EXPORT_THUMB_CACHE_MAX)
        assertEquals(8L * 1024 * 1024, IosProductRootHost.EXPORT_THUMB_BYTES_MAX)
    }

    @Test
    fun decodeFileOrNull_blankOrNonPositive_failsClosed() {
        assertNull(IosExportThumbnailLoader.decodeFileOrNull("", 216))
        assertNull(IosExportThumbnailLoader.decodeFileOrNull("/no/such/path.jpg", 0))
        assertNull(IosExportThumbnailLoader.decodeFileOrNull("/no/such/path.jpg", 96))
    }
}
