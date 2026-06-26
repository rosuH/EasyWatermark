package me.rosuh.easywatermark.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import me.rosuh.easywatermark.render.DesktopWatermarkTextRenderer
import me.rosuh.easywatermark.render.WatermarkGeometry
import java.io.File

/**
 * Desktop (JVM) entry point. Runs the SHARED commonMain engine core on the desktop platform AND, as of
 * S4d-18, renders real watermark **text** artifacts through the shared
 * [me.rosuh.easywatermark.render.WatermarkCellComposer.composeTextCell] path via
 * [DesktopWatermarkTextRenderer] (bundled Latin+CJK font). This is the Desktop half of S4d-17 Option C
 * (Android text stays native; commonMain text is Desktop/iOS-first). The full Compose Desktop editor UI
 * (`Window`, live preview, tiling) is still C4 — this `main` is the renderer scaffold, not the editor.
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

    // S4d-18: render real watermark text cells via the shared commonMain renderer + bundled font,
    // and write them as PNGs so the Desktop text renderer is demonstrably no longer a test-only helper.
    val outDir = File("build/s4d18-desktop-text").apply { mkdirs() }
    val samples = listOf(
        Triple("latin_0", "GOLDEN", 0f),
        Triple("cjk_0", "请勿转载", 0f),
        Triple("multiline_0", "DO NOT\nREDISTRIBUTE", 0f),
        Triple("ascii_315", "GOLDEN", 315f),
        Triple("cjk_315", "请勿转载", 315f),
    )
    println("Rendering Desktop watermark text samples via composeTextCell (bundled Latin+CJK):")
    for ((name, text, deg) in samples) {
        val cell = DesktopWatermarkTextRenderer.renderTextCellResult(text, degree = deg)
        val file = File(outDir, "$name.png").apply { writeBytes(cell.png) }
        println("  $name: ${cell.width}x${cell.height} -> ${file.path} (${cell.png.size} B)")
    }

    // S4d-19: compose FULL watermarked sample images over a generated background through the shared
    // commonMain WatermarkCellComposer.composeOverBackground — REPEAT (tiled) and CLAMP (single decal).
    val composeDir = File("build/s4d19-desktop-watermark").apply { mkdirs() }
    val watermarkText = "请勿转载\nDO NOT REDISTRIBUTE"
    println("Composing Desktop watermarked sample images (640x480) via composeOverBackground:")
    val composed = listOf(
        "repeat_watermark" to DesktopWatermarkComposer.composeSampleResult(
            text = watermarkText, bgWidth = 640, bgHeight = 480, tileMode = WatermarkTileMode.REPEAT,
        ),
        "clamp_watermark" to DesktopWatermarkComposer.composeSampleResult(
            text = watermarkText, bgWidth = 640, bgHeight = 480, tileMode = WatermarkTileMode.CLAMP,
            offsetX = 0.5f, offsetY = 0.5f,
        ),
    )
    for ((name, img) in composed) {
        val file = File(composeDir, "$name.png").apply { writeBytes(img.png) }
        println("  $name: ${img.width}x${img.height} -> ${file.path} (${img.png.size} B)")
    }

    // S4d-20A: REAL-image decode path. Produce a deterministic PNG fixture, write it, then DECODE it back
    // through AWT/ImageIO (DesktopImageDecoder) and watermark the decoded image — the realistic Desktop
    // pipeline: decode (platform) -> render cell + compose (commonMain) -> encode (platform). No binary asset.
    val realDir = File("build/s4d20-desktop-real-image").apply { mkdirs() }
    val fixturePng = DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
    File(realDir, "source_fixture.png").writeBytes(fixturePng)
    println("Desktop real-image decode (ImageIO) + watermark:")
    println("  source_fixture.png: ${fixturePng.size} B -> ${File(realDir, "source_fixture.png").path}")
    val realWatermarked = DesktopWatermarkComposer.composeOverRealImage(
        imageBytes = fixturePng, text = watermarkText, tileMode = WatermarkTileMode.REPEAT,
    )
    val realFile = File(realDir, "real_image_watermark.png").apply { writeBytes(realWatermarked.png) }
    println("  real_image_watermark.png: ${realWatermarked.width}x${realWatermarked.height} -> ${realFile.path} (${realWatermarked.png.size} B)")

    // S4d-80: first APP-ENTRY construction of the common `UserConfigRepository` over the S4d-78 Desktop
    // DataStore factory — read -> update -> read against a real on-disk preferences store. This is an
    // app-level smoke/witness (the real Compose Desktop editor consuming prefs is C4); the read/write
    // roundtrip itself is already gated by `:shared:desktopTest`. Uses a repo-local build dir (NOT
    // ~/.easywatermark) so the smoke leaves no state in the user's home.
    val userConfigDir = File("build/s4d80-desktop-userconfig")
    val userRepo = UserConfigRepository(createUserConfigDataStore(dir = userConfigDir))
    println("Desktop UserConfigRepository (store dir: ${userConfigDir.path}):")
    runBlocking {
        println("  userPreferences (initial): ${userRepo.userPreferences.first()}")
        userRepo.updateFormat(ImageFormat.PNG)
        userRepo.updateCompressLevel(60)
        println("  userPreferences (after update): ${userRepo.userPreferences.first()}")
    }

    println("OK — shared KMP engine core + Desktop text renderer + Desktop composition + real-image decode run on Desktop.")
}
