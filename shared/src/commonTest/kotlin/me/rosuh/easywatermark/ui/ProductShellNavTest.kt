package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.ProductVersion
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProductShellNavTest {

    @Test
    fun aboutBack_fromLaunch_returnsLaunch() {
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.aboutBack(ProductShellNav.Route.Launch),
        )
    }

    @Test
    fun aboutBack_fromEditor_returnsEditor_notLaunch() {
        assertEquals(
            ProductShellNav.Route.Editor,
            ProductShellNav.aboutBack(ProductShellNav.Route.Editor),
        )
        assertNotEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.aboutBack(ProductShellNav.Route.Editor),
        )
    }

    @Test
    fun productVersion_isNotPlatformLabel() {
        assertEquals("3.0.0", ProductVersion.NAME)
        assertNotEquals("iOS", ProductVersion.NAME)
    }

    @Test
    fun mergePickedSelection_replaceVsAppend() {
        val a = ImageInfo(MediaRef("a"))
        val b = ImageInfo(MediaRef("b"))
        val c = ImageInfo(MediaRef("c"))
        assertEquals(listOf(b, c), ProductShellNav.mergePickedSelection(listOf(a), listOf(b, c), append = false))
        assertEquals(listOf(a, b, c), ProductShellNav.mergePickedSelection(listOf(a), listOf(b, c), append = true))
        assertEquals(listOf(a), ProductShellNav.mergePickedSelection(listOf(a), emptyList(), append = true))
    }

    @Test
    fun focusAfterPick_fresh_replaces_focus_with_first_new() {
        val a = ImageInfo(MediaRef("a"))
        val b = ImageInfo(MediaRef("b"))
        val c = ImageInfo(MediaRef("c"))
        val selected = ProductShellNav.mergePickedSelection(listOf(a), listOf(b, c), append = false)
        assertEquals(
            b,
            ProductShellNav.focusAfterPick(selected, append = false, previousCur = a),
            "fresh A→B,C focuses first of the new batch (B), not previous A",
        )
    }

    @Test
    fun focusAfterPick_append_keeps_previous_when_still_present() {
        val a = ImageInfo(MediaRef("a"))
        val b = ImageInfo(MediaRef("b"))
        val selected = ProductShellNav.mergePickedSelection(listOf(a), listOf(b), append = true)
        assertEquals(
            a,
            ProductShellNav.focusAfterPick(selected, append = true, previousCur = a),
            "append must preserve A focus",
        )
    }

    @Test
    fun openAbout_remembersReturnRoute() {
        val (route, ret) = ProductShellNav.openAbout(ProductShellNav.Route.Editor)
        assertEquals(ProductShellNav.Route.About, route)
        assertEquals(ProductShellNav.Route.Editor, ret)
    }

    @Test
    fun overlayBase_about_keepsLaunchOrEditor() {
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.overlayBase(
                ProductShellNav.Route.About,
                ProductShellNav.Route.Launch,
            ),
        )
        assertEquals(
            ProductShellNav.Route.Editor,
            ProductShellNav.overlayBase(
                ProductShellNav.Route.About,
                ProductShellNav.Route.Editor,
            ),
        )
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.overlayBase(
                ProductShellNav.Route.About,
                ProductShellNav.Route.About,
            ),
        )
    }

    @Test
    fun overlayBase_notAbout_isIdentity() {
        assertEquals(
            ProductShellNav.Route.Editor,
            ProductShellNav.overlayBase(
                ProductShellNav.Route.Editor,
                ProductShellNav.Route.Launch,
            ),
        )
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.overlayBase(
                ProductShellNav.Route.Launch,
                ProductShellNav.Route.Editor,
            ),
        )
    }

    @Test
    fun coldLaunchReveal_holdActive_untilRelease() {
        ColdLaunchReveal.resetForTests()
        ColdLaunchReveal.requestHostHold()
        assertEquals(true, ColdLaunchReveal.isHostHoldActive())
        assertEquals(
            true,
            ColdLaunchReveal.shouldPlay(
                consumed = false,
                firstBaseRoute = ProductShellNav.Route.Launch,
            ),
        )
        ColdLaunchReveal.releaseHostHold()
        assertEquals(false, ColdLaunchReveal.isHostHoldActive())
        ColdLaunchReveal.requestHostHold()
        ColdLaunchReveal.resetForTests()
        assertEquals(false, ColdLaunchReveal.isHostHoldActive())
    }

    @Test
    fun coldLaunchReveal_onlyFirstUnconsumedLaunch() {
        assertEquals(
            true,
            ColdLaunchReveal.shouldPlay(consumed = false, firstBaseRoute = ProductShellNav.Route.Launch),
        )
        assertEquals(
            false,
            ColdLaunchReveal.shouldPlay(consumed = true, firstBaseRoute = ProductShellNav.Route.Launch),
        )
        assertEquals(
            false,
            ColdLaunchReveal.shouldPlay(consumed = false, firstBaseRoute = ProductShellNav.Route.Editor),
        )
        assertEquals(
            false,
            ColdLaunchReveal.shouldPlay(consumed = false, firstBaseRoute = ProductShellNav.Route.About),
        )
    }

    @Test
    fun productShellTransitions_aboutKinds() {
        assertEquals(
            ProductShellTransitions.TransitionKind.ToAbout,
            ProductShellTransitions.kind(ProductShellNav.Route.Launch, ProductShellNav.Route.About),
        )
        assertEquals(
            ProductShellTransitions.TransitionKind.FromAbout,
            ProductShellTransitions.kind(ProductShellNav.Route.About, ProductShellNav.Route.Editor),
        )
        assertEquals(
            ProductShellTransitions.TransitionKind.ToEditor,
            ProductShellTransitions.kind(ProductShellNav.Route.Launch, ProductShellNav.Route.Editor),
        )
        assertEquals(
            ProductShellTransitions.TransitionKind.ToLaunch,
            ProductShellTransitions.kind(ProductShellNav.Route.Editor, ProductShellNav.Route.Launch),
        )
    }
}
