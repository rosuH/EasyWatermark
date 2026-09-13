package me.rosuh.easywatermark.ui

/**
 * Process-first Launch appear. Play fade+scale **once** when the first
 * [ProductShellHost] base route in this process is Launch.
 *
 * Not a [LaunchScreen] first-composition flag: Editor→Launch remounts Launch
 * and would replay. Share-in Editor and later About overlay must not replay.
 *
 * Android does not play this fade ([ProductShellHost.playProcessFirstReveal] =
 * false). The first Android frame is opaque Launch so the OS splash can
 * dismiss on first draw (2.x). iOS and Desktop keep the process-first tween.
 */
object ColdLaunchReveal {
    fun shouldPlay(
        consumed: Boolean,
        firstBaseRoute: ProductShellNav.Route,
    ): Boolean = !consumed && firstBaseRoute == ProductShellNav.Route.Launch

    private var processConsumed: Boolean = false

    /** First [ProductShellHost] composition in this process consumes the one-shot. */
    fun observeFirstBase(firstBaseRoute: ProductShellNav.Route): Boolean {
        val play = shouldPlay(processConsumed, firstBaseRoute)
        processConsumed = true
        return play
    }

    fun resetForTests() {
        processConsumed = false
    }
}
