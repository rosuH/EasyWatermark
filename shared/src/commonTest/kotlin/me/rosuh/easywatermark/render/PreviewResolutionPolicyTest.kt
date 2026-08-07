package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewResolutionPolicyTest {

    @Test
    fun committedFitBucket_usesDisplayedImageEdge_notUnusedLetterboxEdge() {
        // A 4:3 source in a very wide, short container is Fit-limited by height: its displayed
        // long edge is 800px, so this must not waste a 1920px decode for the 2400px container.
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1080,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 4000,
                sourceHeightPx = 3000,
                containerWidthPx = 2400,
                containerHeightPx = 600,
            ),
        )
    }

    @Test
    fun committedFitBucket_hasTenPercentHeadroom_andCapsAt1920() {
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1440,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 3000,
                sourceHeightPx = 2000,
                containerWidthPx = 1200,
                containerHeightPx = 1000,
            ),
        )
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1920,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 1000,
                sourceHeightPx = 1000,
                containerWidthPx = 5000,
                containerHeightPx = 5000,
            ),
        )
    }

    @Test
    fun filmstripBuckets_followMeasuredCellPixels() {
        assertEquals(128, PreviewResolutionPolicy.filmstripMaxEdgePx(72))
        assertEquals(160, PreviewResolutionPolicy.filmstripMaxEdgePx(140))
        assertEquals(192, PreviewResolutionPolicy.filmstripMaxEdgePx(300))
    }
}
