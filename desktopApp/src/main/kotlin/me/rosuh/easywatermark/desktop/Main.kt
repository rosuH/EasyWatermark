package me.rosuh.easywatermark.desktop

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.render.WatermarkGeometry

/**
 * Minimal Desktop (JVM) entry point — runs the SHARED commonMain engine core on the desktop
 * platform, demonstrating KMP code reuse end-to-end (the same types/geometry Android uses).
 * This is the C4 desktop scaffold; the Compose Desktop editor UI replaces this `main` later.
 */
fun main() {
    println("EasyWatermark — Desktop (JVM) target, running :shared/commonMain engine core")

    // domain types from commonMain
    val format = ImageFormat.fromStorageId(1)
    check(format == ImageFormat.PNG) { "ImageFormat round-trip failed on desktop" }
    println("  output format (from storageId 1): $format")

    // engine geometry from commonMain — a sample 200x80 text cell rotated 315°, hGap/vGap 20%
    val contentW = 200f
    val contentH = 80f
    val degree = 315f
    val cellW = WatermarkGeometry.rotatedCellWidth(contentW, contentH, degree)
    val cellH = WatermarkGeometry.rotatedCellHeight(contentW, contentH, degree)
    val tileW = WatermarkGeometry.horizontalGap(cellW.toInt(), 20)
    val tileH = WatermarkGeometry.verticalGap(cellH.toInt(), 20)
    println("  cell ${contentW.toInt()}x${contentH.toInt()} @${degree.toInt()}° -> AABB ${cellW.toInt()}x${cellH.toInt()} -> tile ${tileW}x${tileH}")

    val r: Result<String> = Result.success("ok")
    check(r.isSuccess())
    println("  shared Result: success=${r.isSuccess()} data=${r.data}")

    println("OK — shared KMP engine core runs on Desktop.")
}
