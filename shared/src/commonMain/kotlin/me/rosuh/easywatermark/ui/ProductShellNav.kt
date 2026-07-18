package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Pure product-shell navigation helpers shared by Android / iOS / Desktop hosts and tests.
 *
 * Route **UI + transitions** live in [ProductShellHost]. Platforms only keep Activity/window
 * Containers and edge callbacks (pickers, permissions, share). About back must restore the route * that opened About — never infer from unrelated flags.
 */
object ProductShellNav {
    enum class Route {
        Launch,
        Editor,
        About,
    }

    /** [returnTo] is the screen that navigated to About (Launch or Editor). */
    fun aboutBack(returnTo: Route): Route =
        when (returnTo) {
            Route.Launch, Route.Editor -> returnTo
            Route.About -> Route.Launch
        }

    /** Open About and remember where to return. */
    fun openAbout(from: Route): Pair<Route, Route> =
        ProductShellNav.Route.About to when (from) {
            Route.Launch, Route.Editor -> from
            Route.About -> Route.Launch
        }

    /**
 * Merge newly picked images into the session selection.
 * [append] true when the user is already in the editor (add-more); false replaces (launch pick).
     */
    fun mergePickedSelection(
        existing: List<ImageInfo>,
        newly: List<ImageInfo>,
        append: Boolean,
    ): List<ImageInfo> {
        if (newly.isEmpty()) return existing
        return if (append) existing + newly else newly
    }
}
