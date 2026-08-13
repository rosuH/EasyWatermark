package me.rosuh.easywatermark.ui.image

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosHeifDecodePolicyTest {

    @Test
    fun productUi_clampsAndPrefersExtraOverRequestSize() {
        val p = IosHeifDecodePolicy.ProductUi
        assertEquals(128, p.resolveMaxEdgePx(requestLongEdgePx = 0, extraMaxEdgePx = 0))
        assertEquals(200, p.resolveMaxEdgePx(requestLongEdgePx = 200, extraMaxEdgePx = 0))
        assertEquals(96, p.resolveMaxEdgePx(requestLongEdgePx = 200, extraMaxEdgePx = 96))
        assertEquals(64, p.resolveMaxEdgePx(requestLongEdgePx = 16, extraMaxEdgePx = 0))
        assertEquals(512, p.resolveMaxEdgePx(requestLongEdgePx = 4000, extraMaxEdgePx = 0))
        assertFalse(p.resolveIsSampled(sourceLongEdgePx = 4000, outputLongEdgePx = 128))
    }

    @Test
    fun preview_allowsLargeEdges_andInfersSampled() {
        val p = IosHeifDecodePolicy.Preview
        assertEquals(720, p.resolveMaxEdgePx(0, 0))
        assertEquals(1440, p.resolveMaxEdgePx(1440, 0))
        assertEquals(3840, p.resolveMaxEdgePx(8000, 0))
        assertTrue(p.resolveIsSampled(4000, 720))
        assertFalse(p.resolveIsSampled(720, 720))
        assertFalse(p.resolveIsSampled(0, 720))
    }

    @Test
    fun ftypSniff_heicAndJpeg() {
        val heic = Buffer().write(
            byteArrayOf(
                0, 0, 0, 24,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(),
            ),
        )
        assertTrue(IosHeifImageDecoder.looksLikeHeifFtyp(heic))
        val jpeg = Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(12))
        assertFalse(IosHeifImageDecoder.looksLikeHeifFtyp(jpeg))
    }
}
