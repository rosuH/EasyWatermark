package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewWorkingSetBudgetTest {

    private val eightGib = 8L shl 30
    private val threeGib = 3L shl 30

    @Test
    fun bytesPerFrame_isWorstCaseSquareArgb_notFourByThree() {
        assertEquals(
            1920L * 1920L * 4L,
            PreviewWorkingSetBudget.bytesPerFrame(PreviewResolutionPolicy.BUCKET_1920),
        )
        assertEquals(
            720L * 720L * 4L,
            PreviewWorkingSetBudget.bytesPerFrame(PreviewResolutionPolicy.BUCKET_720),
        )
    }

    @Test
    fun bytesPerFrame_coversEveryAspectRatio_soTheFenceCannotUnderCount() {
        val edge = PreviewResolutionPolicy.BUCKET_1920
        val fence = PreviewWorkingSetBudget.bytesPerFrame(edge)
        // Long edge fixed at `edge`; short edge varies with aspect. The old 3:4 model sat at 0.75
        // and under-counted everything at or above 1:1, which is what silently held 3 frames.
        for (shortEdgeRatio in listOf(0.5, 0.75, 0.8, 1.0)) {
            val actual = edge.toLong() * (edge * shortEdgeRatio).toLong() * 4L
            assertTrue(
                actual <= fence,
                "aspect $shortEdgeRatio frame ($actual B) must not exceed the fence ($fence B)",
            )
        }
    }

    @Test
    fun entryCaps_areTheWorkingSetControl() {
        val caps = PreviewWorkingSetBudget.caps(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            eightGib,
        )
        assertEquals(
            PreviewWorkingSetBudget.WORKING_SET_FRAMES,
            caps.watermarkedEntriesMax,
            "watermarked residency must be exactly focus + ±2",
        )
        assertEquals(
            PreviewWorkingSetBudget.WORKING_SET_FRAMES + 1,
            caps.sourceEntriesMax,
            "sources need one slot of headroom for the CLAMP draft decode",
        )
    }

    @Test
    fun phone1920_highMemory_holdsFiveRealFramesPerLayerWithinTheCeiling() {
        val caps = PreviewWorkingSetBudget.caps(
            PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX,
            eightGib,
        )
        // Five worst-case square frames per layer exceed the 128 MiB joint ceiling, so the caps
        // compress evenly. The fence is deliberately generous; entry counts hold the working set.
        assertEquals(caps.sourceBytesMax, caps.watermarkedBytesMax)
        assertEquals(PreviewWorkingSetBudget.HIGH_MEMORY_JOINT_MAX, caps.jointBytesMax)

        // What matters is that real frames at the aspect ratios that reach 1920 still fit five up.
        for ((name, shortEdgeRatio) in listOf("4:3" to 0.75, "5:4" to 0.8)) {
            val realFrame = 1920L * (1920 * shortEdgeRatio).toLong() * 4L
            assertTrue(
                5 * realFrame <= caps.watermarkedBytesMax,
                "$name: five real frames (${5 * realFrame} B) must fit ${caps.watermarkedBytesMax} B",
            )
        }
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
