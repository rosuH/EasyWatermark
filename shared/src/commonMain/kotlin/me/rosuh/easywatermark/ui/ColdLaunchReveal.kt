package me.rosuh.easywatermark.ui

/**
 * Process-first Launch appear. Play fade+scale **once** when the first
 * [ProductShellHost] base route in this process is Launch.
 *
 * Not a [LaunchScreen] first-composition flag: Editor→Launch remounts Launch
 * and would replay. Share-in Editor and later About overlay must not replay.
 */
object ColdLaunchReveal {
    fun shouldPlay(
        consumed: Boolean,
        firstBaseRoute: ProductShellNav.Route,
    ): Boolean = !consumed && firstBaseRoute == ProductShellNav.Route.Launch

    private var processConsumed: Boolean = false
    private var hold: Boolean = false
    private var holdListener: (() -> Unit)? = null

    /** First [ProductShellHost] composition in this process consumes the one-shot. */
    fun observeFirstBase(firstBaseRoute: ProductShellNav.Route): Boolean {
        val play = shouldPlay(processConsumed, firstBaseRoute)
        processConsumed = true
        return play
    }

    /** Android calls once before setContent when splash will hold until Launch first_screen. */
    fun requestHostHold() {
        hold = true
    }

    /** Android splash [OnExitAnimationListener] after remove(). */
    fun releaseHostHold() {
        hold = false
        holdListener?.invoke()
    }

    fun isHostHoldActive(): Boolean = hold

    internal fun setHoldListener(listener: (() -> Unit)?) {
        holdListener = listener
    }

    fun resetForTests() {
        processConsumed = false
        hold = false
        holdListener = null
    }
}
