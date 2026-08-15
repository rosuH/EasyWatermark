package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural: About must overlay a live Launch/Editor tree, not replace it.
 */
class ProductShellHostOverlayTest {

    @Test
    fun host_uses_about_overlay_not_route_swap() {
        val src = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/ProductShellHost.kt",
            "src/commonMain/kotlin/me/rosuh/easywatermark/ui/ProductShellHost.kt",
        )
        assertTrue(src.contains("overlayBase"), "About must keep overlayBase under-screen")
        assertTrue(src.contains("LocalShellObscured"), "under-layer mesh must see LocalShellObscured")
        assertTrue(src.contains("aboutOverlay"), "About cover transition label")
        assertTrue(
            src.contains("productShellBase"),
            "Launch↔Editor AnimatedContent must key the base route, not About",
        )
        assertTrue(
            src.contains("if (aboutPresent)"),
            "under graphicsLayer must be About-cover only — not around Launch↔Editor",
        )
        assertTrue(
            src.contains("aboutPresent || baseBusy"),
            "mesh pause must cover About overlay and the Launch↔Editor swap",
        )
        assertTrue(
            !Regex("""AnimatedContent\(\s*targetState\s*=\s*route""").containsMatchIn(src),
            "must not AnimatedContent(route) — that disposes Launch under About",
        )
    }

    @Test
    fun brandLogo_does_not_reset_meshReady_on_obscured() {
        val src = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/BrandLogo.kt",
            "src/commonMain/kotlin/me/rosuh/easywatermark/ui/BrandLogo.kt",
        )
        assertTrue(src.contains("LocalShellObscured"))
        assertTrue(
            src.contains("LaunchedEffect(animate, motionOk)"),
            "meshReady must not key on obscured",
        )
        assertTrue(src.contains("&& !obscured"))
    }

    private fun readFirst(vararg paths: String): String {
        val cwd = File("").absoluteFile
        val candidates = paths.flatMap { path ->
            listOf(File(path), File(cwd, path), File(cwd.parentFile, path))
        }
        return candidates.first { it.isFile }.readText()
    }
}
