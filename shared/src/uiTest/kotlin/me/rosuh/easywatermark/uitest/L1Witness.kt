package me.rosuh.easywatermark.uitest

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * Evidence-only L1 frame dump (ADR-0010 / ADR-0032). No byte or hash assertions.
 *
 * File write uses okio so the same helper compiles on desktop and iOS. Desktop
 * `desktopTest` cwd is typically `shared/`; iOS simulator may not be able to
 * create the repo build dir — write failures are swallowed so the case still
 * asserts UI behavior.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.captureL1Witness(
    testName: String,
    tag: String = "sharedComposeEditorScreen",
) {
    L1Live.activeTest = testName
    val bitmap = onNodeWithTag(tag, useUnmergedTree = true).captureToImage()
    val bytes = encodePng(bitmap)
    writeL1WitnessPng(testName, bytes)
    L1Live.write(testName, tag, bytes, keyframe = true)
}

internal fun encodePng(bitmap: ImageBitmap): ByteArray {
    val data = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
        .encodeToData(EncodedImageFormat.PNG)
        ?: error("L1 witness PNG encode returned null")
    return data.bytes
}

internal fun writeL1WitnessPng(testName: String, bytes: ByteArray) {
    val dir = resolveL1WitnessDir() ?: return
    try {
        FileSystem.SYSTEM.createDirectories(dir)
        FileSystem.SYSTEM.write(dir / "$testName.png") { write(bytes) }
    } catch (_: Throwable) {
        // iOS simulator / unwritable cwd — desktopTest is the human-view target.
    }
}

internal fun resolveL1WitnessDir(): Path? {
    val start = try {
        FileSystem.SYSTEM.canonicalize(".".toPath())
    } catch (_: Throwable) {
        return null
    }
    var walk: Path? = start
    repeat(8) {
        val here = walk ?: return null
        val settings = here / "settings.gradle.kts"
        if (FileSystem.SYSTEM.exists(settings)) {
            return here / "shared" / "build" / "l1-witness"
        }
        if (here.name == "shared" && FileSystem.SYSTEM.exists(here / "build.gradle.kts")) {
            return here / "build" / "l1-witness"
        }
        walk = here.parent
    }
    return start / "build" / "l1-witness"
}
