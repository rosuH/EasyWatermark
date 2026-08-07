# Deep Link Static URI Recipe

This recipe demonstrates how deep link with a static Uri.

## Recipe components

The recipe contains two activities:

1. `StaticUriDeepLinkActivity` to construct and start an Intent with the deep link uri
2. `MainActivity` is the target Activity of the deep link, represents an app that users can deep link to.

## How the demonstrated deep link works

1. The deep link source (`StaticUriDeepLinkActivity`) defines the uri and creates an Intent to deep link with.
2. The app (`MainActivity`) declares a navigation key (`HomeKey`). To indicate that `HomeKey` supports deep linking, the app declares a `UriDeepLinkMatcher` with the `HomeKey` serializer along with the uri pattern that `HomeKey` supports.
3. `MainActivity` onCreate instantiates a `DeepLinkRequest` with the intent and matches it with the `UriDeepLinkMatcher` to get a `MatchResult`. If the `MatchResult` is non-null, the app navigates to the key returned by the result. Otherwise, the deep link is not supported and the app navigates to a `Fallback` screen.

[![](https://developer.android.com/static/images/picto-icons/code.svg) Explore View the full recipe on GitHub.](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/deeplink/handlerequests/staticuri)

```
package com.example.nav3recipes.deeplink.handlerequests.staticuri

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.nav3recipes.common.deeplink.EntryScreen
import com.example.nav3recipes.common.deeplink.PaddedButton
import com.example.nav3recipes.common.deeplink.TextContent
import com.example.nav3recipes.deeplink.handlerequests.uriarguments.ui.PATH_BASE
import com.example.nav3recipes.ui.setEdgeToEdgeConfig

const val HOME_URI = "$PATH_BASE/home"

class StaticUriDeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setEdgeToEdgeConfig()
        super.onCreate(savedInstanceState)

        setContent {
            EntryScreen("Deep link url:") {
                TextContent(HOME_URI)
                PaddedButton("Deeplink Away!", onClick = dropUnlessResumed {
                    val intent = Intent(
                        this@StaticUriDeepLinkActivity,
                        MainActivity::class.java
                    )
                    // the uri to deep link with
                    intent.data = HOME_URI.toUri()
                    startActivity(intent)
                })
            }
        }
    }
}

   
```

```
package com.example.nav3recipes.deeplink.handlerequests.staticuri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.nav3recipes.common.deeplink.EntryScreen
import com.example.nav3recipes.common.deeplink.TextContent
import com.example.nav3recipes.deeplink.handlerequests.uriarguments.HomeKey
import com.example.nav3recipes.deeplink.handlerequests.uriarguments.NavRecipeKey
import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.DeepLinkUri
import androidx.navigation3.runtime.deeplink.UriDeepLinkMatcher
import androidx.navigation3.runtime.deeplink.invoke
import com.example.nav3recipes.ui.setEdgeToEdgeConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer


@Serializable
internal object FallbackKey: NavRecipeKey {
    override val name: String = "Fallback Key"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setEdgeToEdgeConfig()
        super.onCreate(savedInstanceState)

        // create a DeepLinkRequest with the intent
        val request = DeepLinkRequest(intent)

        // try to match DeepLinkRequest to a DeepLinkMatcher
        val matchResult = HOME_MATCHER.match(request)
        val key = matchResult?.key ?: FallbackKey

        /**
         * Then pass starting key to backstack
         */
        setContent {
            val backStack: NavBackStack<NavKey> = rememberNavBackStack(key)
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider {
                    entry<HomeKey> { key ->
                        EntryScreen(key.name) {
                            TextContent("Deep linked to Home")
                        }
                    }
                    entry<FallbackKey> { key ->
                        EntryScreen("${key.name} ") {
                            TextContent(
                                "Failed to deep link - DeepLinkRequest " +
                                    "did not match with any DeepLinkMatcher"
                            )
                        }
                    }

                }
            )
        }
    }
}

/**
 * Each matcher is associated with a navigation key that supports this deep link.
 *
 * A navigation key can be associated with multiple DeepLinkMatchers if it supports more than one deep link.
 */
private val HOME_MATCHER = UriDeepLinkMatcher(
    uriPattern = DeepLinkUri(HOME_URI),
    serializer = serializer<HomeKey>(),
)
   
```