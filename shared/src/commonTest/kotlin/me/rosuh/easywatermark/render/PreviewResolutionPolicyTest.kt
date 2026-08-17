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
    fun committedFitBucket_hasTenPercentHeadroom_andCapsAt3840() {
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1440,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 3000,
                sourceHeightPx = 2000,
                containerWidthPx = 1200,
                containerHeightPx = 1000,
            ),
        )
        // Huge container + large source → highest desktop bucket (not full export).
        assertEquals(
            PreviewResolutionPolicy.BUCKET_3840,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 8000,
                sourceHeightPx = 8000,
                containerWidthPx = 5000,
                containerHeightPx = 5000,
            ),
        )
        // Mid large pane (~2200 need) → 2560 bucket.
        assertEquals(
            PreviewResolutionPolicy.BUCKET_2560,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 4000,
                sourceHeightPx = 3000,
                containerWidthPx = 2000,
                containerHeightPx = 1500,
            ),
        )
    }

    @Test
    fun phoneMaxLongEdge_capsHugeContainerAt1920() {
        assertEquals(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            PreviewResolutionPolicy.committedMaxEdgePx(
                previewBoxWidthPx = 1206,
                previewBoxHeightPx = 2622,
                maxLongEdgePx = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            ),
        )
        assertEquals(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 1,
                sourceHeightPx = 1,
                containerWidthPx = 1206,
                containerHeightPx = 2622,
                maxLongEdgePx = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            ),
        )
        assertEquals(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 8000,
                sourceHeightPx = 8000,
                containerWidthPx = 5000,
                containerHeightPx = 5000,
                maxLongEdgePx = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            ),
        )
    }

    @Test
    fun missingSourceDims_fallBackToContainerBucket() {
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1920,
            PreviewResolutionPolicy.committedMaxEdgePxForFit(
                sourceWidthPx = 1,
                sourceHeightPx = 1,
                containerWidthPx = 1600,
                containerHeightPx = 900,
            ),
        )
    }

    @Test
    fun filmstripBuckets_followMeasuredCellPixels() {
        assertEquals(128, PreviewResolutionPolicy.filmstripMaxEdgePx(72))
        assertEquals(160, PreviewResolutionPolicy.filmstripMaxEdgePx(140))
        assertEquals(192, PreviewResolutionPolicy.filmstripMaxEdgePx(300))
    }

    @Test
    fun draft_floor_rises_on_large_committed_panes() {
        assertEquals(
            PreviewResolutionPolicy.DRAFT_MAX_EDGE_PX,
            PreviewResolutionPolicy.draftMaxEdgePx(PreviewResolutionPolicy.BUCKET_720),
        )
        assertEquals(
            PreviewResolutionPolicy.BUCKET_1080,
            PreviewResolutionPolicy.draftMaxEdgePx(PreviewResolutionPolicy.BUCKET_1920),
        )
    }

    @Test
    fun lowResolutionSource_flagsChatSizedPhotos_notUnknownOrCameraStills() {
        assertEquals(true, PreviewResolutionPolicy.isLowResolutionSource(480, 320))
        assertEquals(true, PreviewResolutionPolicy.isLowResolutionSource(480, 319))
        assertEquals(true, PreviewResolutionPolicy.isLowResolutionSource(719, 400))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(720, 720))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(1920, 1080))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(1080, 8000))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(1, 1))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(0, 0))
        assertEquals(false, PreviewResolutionPolicy.isLowResolutionSource(-1, 480))
    }
}
