package me.rosuh.easywatermark.render

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Filmstrip neighbor indices around [focus] (exclusive), in production order:
 * −radius … −1, then +1 … +radius. Out-of-range indices are dropped.
 *
 * Used by Desktop / Android / iOS focus±2 Watermarked warm. Not PhotoKit.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun neighborIndices(focus: Int, size: Int, radius: Int = 2): List<Int> {
    if (focus !in 0 until size || radius < 1) return emptyList()
    return buildList {
        for (delta in -radius..radius) {
            if (delta == 0) continue
            val i = focus + delta
            if (i in 0 until size) add(i)
        }
    }
}
