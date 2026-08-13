package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewWorkingSetBudgetTest {

    private val eightGib = 8L shl 30
    private val threeGib = 3L shl 30

    @Test
    fun bytesPerFrame_isFourByThreeArgb() {
        assertEquals(
            1920L * 1440L * 4L,
            PreviewWorkingSetBudget.bytesPerFrame(PreviewResolutionPolicy.BUCKET_1920),
        )
        assertEquals(
            720L * 540L * 4L,
            PreviewWorkingSetBudget.bytesPerFrame(PreviewResolutionPolicy.BUCKET_720),
        )
    }

    @Test
    fun phone1920_highMemory_holdsFiveFramesPerLayer() {
        val caps = PreviewWorkingSetBudget.caps(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            eightGib,
        )
        val five = 5 * PreviewWorkingSetBudget.bytesPerFrame(1920)
        assertEquals(five, caps.sourceBytesMax)
        assertEquals(five, caps.watermarkedBytesMax)
        assertEquals(five * 2, caps.jointBytesMax)
        assertTrue(caps.jointBytesMax <= PreviewWorkingSetBudget.HIGH_MEMORY_JOINT_MAX)
        assertTrue(caps.jointBytesMax > 100L * 1024 * 1024)
    }

    @Test
    fun pane720_keepsHistoricalFloors() {
        val caps = PreviewWorkingSetBudget.caps(PreviewResolutionPolicy.BUCKET_720, eightGib)
        assertEquals(PreviewWorkingSetBudget.SOURCE_BYTES_FLOOR, caps.sourceBytesMax)
        assertEquals(PreviewWorkingSetBudget.WATERMARKED_BYTES_FLOOR, caps.watermarkedBytesMax)
        assertEquals(
            PreviewWorkingSetBudget.SOURCE_BYTES_FLOOR +
                PreviewWorkingSetBudget.WATERMARKED_BYTES_FLOOR,
            caps.jointBytesMax,
        )
    }

    @Test
    fun lowMemory_compressesJointTo64MiB() {
        val caps = PreviewWorkingSetBudget.caps(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            threeGib,
        )
        assertTrue(caps.jointBytesMax <= PreviewWorkingSetBudget.LOW_MEMORY_JOINT_MAX)
        assertEquals(
            caps.sourceBytesMax + caps.watermarkedBytesMax,
            caps.jointBytesMax,
        )
    }

    @Test
    fun large3840_highMemory_capsJointAt128MiB() {
        val caps = PreviewWorkingSetBudget.caps(
            PreviewResolutionPolicy.BUCKET_3840,
            eightGib,
        )
        assertTrue(caps.jointBytesMax <= PreviewWorkingSetBudget.HIGH_MEMORY_JOINT_MAX)
        assertTrue(
            5 * PreviewWorkingSetBudget.bytesPerFrame(3840) > caps.watermarkedBytesMax,
            "3840 five-frame formula must be compressed by the 128 MiB joint ceiling",
        )
    }

    @Test
    fun phoneMaxLongEdge_stays1920() {
        assertEquals(1920, PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX)
    }
}
