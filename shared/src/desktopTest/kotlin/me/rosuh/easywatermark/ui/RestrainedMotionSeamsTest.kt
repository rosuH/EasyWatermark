package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural contracts for the four restrained-motion seams.
 * Source-level: no device, no screenshot.
 */
class RestrainedMotionSeamsTest {

    @Test
    fun dialog_usesFadeScaleAndWaitsExit() {
        val src = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/EwmContentDialog.kt",
        )
        assertTrue(src.contains("fadeIn"), "dialog enter must fade")
        assertTrue(src.contains("scaleIn"), "dialog enter must scale")
        assertTrue(src.contains("fadeOut") && src.contains("scaleOut"), "dialog exit must be symmetric")
        assertTrue(src.contains("contentEnterScale") || src.contains("0.97"), "scale floor 0.97")
        assertTrue(src.contains("shellShortMs"), "dialog duration is shellShortMs")
        assertTrue(src.contains("motionDurationMs"), "dialog honors MotionPolicy")
        assertTrue(src.contains("FastOutSlowInEasing"), "existing easing, not a new curve")
        assertTrue(src.contains("delay("), "dismiss must wait exit before dispose")
    }

    @Test
    fun openSource_sharedOverlayOnThreeHosts() {
        val overlay = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/about/OpenSourceOverlayHost.kt",
        )
        val android = read("app/src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt")
        val desktop = read(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        val ios = read(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt",
        )
        val shell = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/ProductShellHost.kt",
        )
        assertTrue(overlay.contains("OpenSourceOverlayHost"), "shared host exists")
        assertTrue(overlay.contains("openSourceEnter") && overlay.contains("openSourceExit"))
        assertTrue(overlay.contains("visible = visible"), "keep composed so exit can play")
        assertTrue(android.contains("OpenSourceOverlayHost("))
        assertTrue(desktop.contains("OpenSourceOverlayHost("))
        assertTrue(ios.contains("OpenSourceOverlayHost("))
        assertFalse(
            Regex("""if\s*\(\s*showOpenSource\s*\)\s*\{[\s\S]*OpenSourceScreen\(""").containsMatchIn(android),
            "Android must not hard-cut OpenSourceScreen",
        )
        assertFalse(
            Regex("""if\s*\(\s*showOpenSource\s*\)\s*\{[\s\S]*OpenSourceScreen\(""").containsMatchIn(desktop),
            "Desktop must not hard-cut OpenSourceScreen",
        )
        assertFalse(
            Regex("""if\s*\(\s*showOpenSource\s*\)\s*\{[\s\S]*OpenSourceScreen\(""").containsMatchIn(ios),
            "iOS must not hard-cut OpenSourceScreen",
        )
        assertTrue(shell.contains("fun openSourceEnter"))
        assertTrue(shell.contains("fun openSourceExit"))
        assertTrue(
            shell.contains("slideInHorizontally") && shell.contains("fadeIn"),
            "open source uses short H-slide+fade",
        )
        val openSourceBlock = shell.substringAfter("fun openSourceEnter").substringBefore("fun transform")
        assertFalse(
            openSourceBlock.contains("UnderCoveredScale") || openSourceBlock.contains("0.75"),
            "Open Source must not use About 0.75/0.5 cover",
        )
    }

    @Test
    fun editorTopBar_pressedAlphaKeepsTestTags() {
        val src = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/EditorTopBar.kt",
        )
        assertTrue(src.contains("collectIsPressedAsState"))
        assertTrue(src.contains("0.45f") || src.contains("PressedIconAlpha"))
        assertTrue(src.contains("sharedComposeAddMoreButton"))
        assertTrue(src.contains("sharedComposeSaveButton"))
        assertFalse(src.contains("hover"), "no hover scale")
    }

    @Test
    fun coldLaunch_oneshotInProductShellHost() {
        val host = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/ProductShellHost.kt",
        )
        val gate = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/ColdLaunchReveal.kt",
        )
        val launch = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/LaunchScreen.kt",
        )
        assertTrue(gate.contains("shouldPlay"))
        assertTrue(gate.contains("observeFirstBase"))
        assertTrue(gate.contains("requestHostHold"))
        assertTrue(gate.contains("releaseHostHold"))
        assertTrue(gate.contains("isHostHoldActive"))
        assertTrue(host.contains("ColdLaunchReveal.observeFirstBase"))
        assertTrue(host.contains("isHostHoldActive"))
        assertTrue(host.contains("contentEnterScale"))
        assertTrue(host.contains("coldLayerActive"))
        assertFalse(
            launch.contains("ColdLaunchReveal") || launch.contains("contentEnterScale"),
            "do not one-shot cold reveal on LaunchScreen first composition",
        )
    }

    private fun resolveRepoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found from user.dir=$cwd")
    }

    private fun read(path: String): String = resolveRepoFile(path).readText()
}
