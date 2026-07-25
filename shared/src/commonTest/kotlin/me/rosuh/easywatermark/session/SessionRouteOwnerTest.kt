package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.ui.LaunchScreenUiState
import me.rosuh.easywatermark.ui.ProductShellNav
import kotlin.test.Test
import kotlin.test.assertEquals

/** E0 mapping: Session LaunchScreenUiState → shell ProductShellNav.Route. */
class SessionRouteOwnerTest {

    @Test
    fun routeFromLaunchUi_mapsAllSessionStates() {
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.routeFromLaunchUi(LaunchScreenUiState.Launch),
        )
        assertEquals(
            ProductShellNav.Route.Launch,
            ProductShellNav.routeFromLaunchUi(LaunchScreenUiState.GalleryDialog),
        )
        assertEquals(
            ProductShellNav.Route.Editor,
            ProductShellNav.routeFromLaunchUi(LaunchScreenUiState.Editor),
        )
        assertEquals(
            ProductShellNav.Route.About,
            ProductShellNav.routeFromLaunchUi(LaunchScreenUiState.About),
        )
    }

    @Test
    fun aboutReturnUi_normalizesNonLaunchEditor() {
        assertEquals(
            LaunchScreenUiState.Launch,
            ProductShellNav.aboutReturnUi(LaunchScreenUiState.GalleryDialog),
        )
        assertEquals(
            LaunchScreenUiState.Editor,
            ProductShellNav.aboutReturnUi(LaunchScreenUiState.Editor),
        )
    }
}
