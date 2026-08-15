package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Pure product-shell navigation helpers shared by Android / iOS / Desktop hosts and tests.
 *
 * Route **UI + transitions** live in [ProductShellHost]. Session owns product route via
 * [LaunchScreenUiState] (E0); hosts map with [routeFromLaunchUi]. About is an overlay on
 * the live Launch/Editor tree ([overlayBase]); these helpers are pure transition aids
 * only — not a second route owner.
 */
object ProductShellNav {
    enum class Route {
        Launch,
        Editor,
        About,
    }

    /** Map Session-owned [LaunchScreenUiState] to shell animation route. */
    fun routeFromLaunchUi(ui: LaunchScreenUiState): Route = when (ui) {
        LaunchScreenUiState.Launch,
        LaunchScreenUiState.GalleryDialog,
        -> Route.Launch
        LaunchScreenUiState.Editor -> Route.Editor
        LaunchScreenUiState.About -> Route.About
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

    /** Session-side return target for About (Launch or Editor only). */
    fun aboutReturnUi(from: LaunchScreenUiState): LaunchScreenUiState = when (from) {
        LaunchScreenUiState.Launch, LaunchScreenUiState.Editor -> from
        LaunchScreenUiState.GalleryDialog, LaunchScreenUiState.About -> LaunchScreenUiState.Launch
    }

    /**
     * Screen that stays composed under an About overlay.
     * About is not a replacement route — [aboutReturn] (Launch or Editor) stays live.
     */
    fun overlayBase(route: Route, aboutReturn: Route): Route =
        if (route == Route.About) aboutBack(aboutReturn) else route

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

    /**
     * Focus after a picker batch is committed into [selected].
     *
     * - Fresh replace (`append=false`): first item (EnterEditor default).
     * - Append (`append=true`): keep [previousCur] when it remains in the list so add-more does
     *   not snap the filmstrip back to index 0.
     *
     * Issue 26 K1/K2: pure selection identity; does not own path/cache bytes.
     */
    fun focusAfterPick(
        selected: List<ImageInfo>,
        append: Boolean,
        previousCur: ImageInfo?,
    ): ImageInfo? {
        if (selected.isEmpty()) return null
        if (append && previousCur != null) {
            val kept = selected.firstOrNull { it.uri == previousCur.uri }
            if (kept != null) return kept
        }
        return selected.first()
    }
}
