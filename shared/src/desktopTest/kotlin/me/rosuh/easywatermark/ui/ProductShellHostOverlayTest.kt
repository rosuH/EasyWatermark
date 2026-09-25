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
        assertTrue(
            src.contains("PointerEventPass.Final"),
            "About overlay must consume leftover pointers after children, not before",
        )
        assertTrue(
            src.contains("ewmTestTagsAsResourceId"),
            "shell root must expose testTags as resource-ids",
        )
        assertTrue(
            !src.contains("event.changes.forEach { it.consume() }"),
            "must not consume-all on a sibling Box in front of About",
        )
        assertTrue(src.contains("LocalAboutBackBinder"), "About must register onBack with the shell")
        assertTrue(
            src.contains(".testTag(\"aboutBack\")"),
            "rest-positioned aboutBack hit target must keep the test tag",
        )
    }

    @Test
    fun android_recovery_close_recreates_activity() {
        val src = readFirst("app/src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt")
        assertTrue(src.contains("onCloseRecovery"))
        assertTrue(
            src.contains("recreate()"),
            "closing recovery must leave the recovery setContent branch",
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
        assertTrue(src.contains("mutableFloatStateOf"))
        assertTrue(src.contains("delay("), "mesh must not pin a 60fps InfiniteTransition clock")
        assertTrue(!src.contains("rememberInfiniteTransition"))
    }

    @Test
    fun recovery_and_openSource_expose_test_tags_as_resource_ids() {
        val recovery = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/RecoveryScreen.kt",
        )
        val openSource = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/about/OpenSourceOverlayHost.kt",
        )
        val carousel = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/EditorOptionCarousel.kt",
        )
        assertTrue(recovery.contains("ewmTestTagsAsResourceId"))
        assertTrue(recovery.contains("sharedComposeRecoveryClose"))
        assertTrue(openSource.contains("ewmTestTagsAsResourceId"))
        assertTrue(
            carousel.contains("clickable { onOptionSelected(item) }"),
        )
        val clickableAt = carousel.indexOf("clickable { onOptionSelected(item) }")
        val testTagAt = carousel.indexOf("itemTestTag?.invoke(item)?.let { Modifier.testTag(it) }")
        val mergeAt = carousel.indexOf("semantics(mergeDescendants = true)")
        assertTrue(clickableAt >= 0 && testTagAt > clickableAt && mergeAt > testTagAt)
    }

    @Test
    fun ios_store_seed_hooks_sit_below_back_button() {
        val src = readFirst("iosApp/iosApp/ContentView.swift")
        assertTrue(src.contains("store-seed-"))
        assertTrue(
            src.contains("constant: 96"),
            "store-seed hook bar must sit below the 48pt back button",
        )
        assertTrue(
            src.contains("hit is UIButton"),
            "hook bar must pass hits through to Compose except on the seed buttons",
        )
    }

    private fun readFirst(vararg paths: String): String {
        val cwd = File("").absoluteFile
        val candidates = paths.flatMap { path ->
            listOf(File(path), File(cwd, path), File(cwd.parentFile, path))
        }
        return candidates.first { it.isFile }.readText()
    }
}
