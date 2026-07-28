package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mutation-resistant bucket boundaries for adaptive committed previews.
 */
class PreviewResolutionPolicyTest {

    @Test
    fun invalidOrUnmeasured_mapsTo720() {
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(0, 0))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(-1, 800))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(800, 0))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(0, 1206))
    }

    @Test
    fun atOrBelow720_mapsTo720() {
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(1, 1))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(720, 400))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(400, 720))
        assertEquals(720, PreviewResolutionPolicy.committedMaxEdgePx(719, 719))
    }

    @Test
    fun range721To1080_mapsTo1080() {
        assertEquals(1080, PreviewResolutionPolicy.committedMaxEdgePx(721, 400))
        assertEquals(1080, PreviewResolutionPolicy.committedMaxEdgePx(1080, 600))
        assertEquals(1080, PreviewResolutionPolicy.committedMaxEdgePx(900, 1080))
    }

    @Test
    fun range1081To1440_mapsTo1440_includingIphone16ProWidth() {
        assertEquals(1440, PreviewResolutionPolicy.committedMaxEdgePx(1081, 500))
        // iPhone 16 Pro ~1206px-wide preview *box* (not full-screen long edge) → 1440 bucket.
        assertEquals(1440, PreviewResolutionPolicy.committedMaxEdgePx(1206, 800))
        assertEquals(1440, PreviewResolutionPolicy.committedMaxEdgePx(800, 1206))
        assertEquals(1440, PreviewResolutionPolicy.committedMaxEdgePx(1440, 900))
    }

    @Test
    fun above1440_capsAt1920() {
        assertEquals(1920, PreviewResolutionPolicy.committedMaxEdgePx(1441, 900))
        assertEquals(1920, PreviewResolutionPolicy.committedMaxEdgePx(2000, 3000))
        assertEquals(1920, PreviewResolutionPolicy.committedMaxEdgePx(4096, 2160))
    }

    @Test
    fun maxEdgeForPaint_draftAlways720_committedUsesBucket() {
        assertEquals(720, PreviewResolutionPolicy.maxEdgeForPaint(isDraft = true, committedBucketPx = 1440))
        assertEquals(720, PreviewResolutionPolicy.maxEdgeForPaint(isDraft = true, committedBucketPx = 1920))
        assertEquals(1440, PreviewResolutionPolicy.maxEdgeForPaint(isDraft = false, committedBucketPx = 1440))
        assertEquals(1920, PreviewResolutionPolicy.maxEdgeForPaint(isDraft = false, committedBucketPx = 1920))
        assertEquals(720, PreviewResolutionPolicy.maxEdgeForPaint(isDraft = false, committedBucketPx = 0))
    }
}
