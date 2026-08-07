# Deep Link Synthetic BackStack Recipe

This recipe demonstrates how to apply the principles of navigation in the context of deep links by managing a synthetic backStack and Task stacks.

# Recipe Structure

This recipe simulates a real-world scenario where "App A" deep links into "App B".

"App A" is simulated by the module [syntheticbackstack](https://developer.android.com/app/src/main/java/com/example/nav3recipes/deeplink/handlerequests/syntheticbackstack), which contains the `SyntheticBackStackDeepLinkActivity` that allows you to create a deeplink intent and trigger that in either the existing Task, or in a new Task.

"App B" is simulated by the module [syntheticbackstackapp](https://developer.android.com/syntheticbackstackapp/src/main/java/com/example/nav3recipes/deeplink/syntheticbackstack), which contains the `SyntheticBackStackAppActivity` that you deeplink into. That module shows you how to build a synthetic backStack and how to manage the Task stack properly in order to support both Back and Up buttons.

# How to Use

Ensure both the main `app` and `syntheticbackstackapp` are installed on the emulator or connected device. Ensure that the installed `syntheticbackstackapp` supports the `"www.nav3deeplink.com"` link.

On the recipe's landing page, choose the filters and click the button to deep link. It should bring you to the Activity of `syntheticbackstackapp`.

# How it Works

The recipe follows the deep link guideline summarized [here](https://developer.android.com/docs/deeplink-guide#summary).

To see behavior of `Existing Task`:

1. Open deep link using current task
2. On the device, swipe up to see all recent apps
3. Notice that the new Activity is opened within the Nav3Recipes app
4. Click back button to go back to the original Activity
5. Repeat step 1
6. Click the up button to go to parent screen
7. On the device, swipe up to see all recent apps
8. Notice that the new Activity is now opened within the Nav3SyntheticBackStack app

To see behavior of `New Task`:

1. Open deep link using new task
2. On the device, swipe up to see all recent apps
3. Notice that the new Activity is opened within the Nav3SyntheticBackStack app
4. Click Up or Back button to go to parent screen

# Core implementation

The core helper functions for navigateUp and building synthetic backStack can be found [here](https://developer.android.com/syntheticbackstackapp/src/main/java/com/example/nav3recipes/deeplink/syntheticbackstack/util)

# Further Read

Check out the [deep link guide](https://developer.android.com/docs/deeplink-guide) for a comprehensive guide on Deep linking principles and how to apply them in Navigation 3.
[![](https://developer.android.com/static/images/picto-icons/code.svg) Explore View the full recipe on GitHub.](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/deeplink/handlerequests/syntheticbackstack)

```
package com.example.nav3recipes.deeplink.handlerequests.syntheticbackstack

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.nav3recipes.common.deeplink.EntryScreen
import com.example.nav3recipes.common.deeplink.LIST_FIRST_NAMES
import com.example.nav3recipes.common.deeplink.LIST_LOCATIONS
import com.example.nav3recipes.common.deeplink.MenuDropDown
import com.example.nav3recipes.common.deeplink.PaddedButton
import com.example.nav3recipes.common.deeplink.TextContent
import com.example.nav3recipes.ui.setEdgeToEdgeConfig

internal const val ADVANCED_PATH_BASE = "https://www.nav3deeplink.com"

/**
 * The recipe entry point that allows users to create a deep link and make a request with it.
 *
 * **HOW THIS RECIPE WORKS** This recipe simulates a real-world scenario where "App A" deep links
 * into "App B".
 *
 * "App A" is simulated by this current module `syntheticbackstack`, which
 * contains the [SyntheticBackStackDeepLinkActivity] that allows you to create a deeplink intent and
 * trigger that in either the existing Task, or in a new Task.
 *
 * "App B" is simulated by the module `syntheticbackstackapp`, which contains
 * the SyntheticBackStackAppActivity that you deeplink into. That module shows you how to build a synthetic backStack
 * and how to manage the Task stack properly in order to support both Back and Up buttons.
 *
 * See the [README](README.md) file of current module for more info on advanced deep linking.
 */
class SyntheticBackStackDeepLinkActivity: ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setEdgeToEdgeConfig()
        super.onCreate(savedInstanceState)

        setContent {
            EntryScreen("Sandbox - Build Your Deeplink Intent") {
                val initFirstName = MENU_OPTIONS_FIRST_NAME.values.first().first()
                val initLocation = MENU_OPTIONS_LOCATION.values.last().first()
                val initTaskStack = MENU_OPTIONS_TASK_STACK.values.first().first()
                var firstName by remember { mutableStateOf(initFirstName) }
                var location by remember { mutableStateOf(initLocation) }
                var taskStack by remember { mutableStateOf(initTaskStack) }

                // select first name
                MenuDropDown(
                    menuOptions = MENU_OPTIONS_FIRST_NAME,
                ) { _, selected ->
                    firstName = selected
                }

                // select first name
                MenuDropDown(
                    menuOptions = MENU_OPTIONS_LOCATION,
                ) { _, selected ->
                    location = selected
                }

                // select current task stack or build new task stack
                MenuDropDown(
                    menuOptions = MENU_OPTIONS_TASK_STACK,
                ) { _, selected ->
                    taskStack = selected
                }

                // build final deeplink URL and Intent
                val finalUrl = "${ADVANCED_PATH_BASE}/user/$firstName/$location"

                // display Intent info
                val flagString = if (taskStack == TAG_NEW_TASK) {
                    "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"
                } else "<none>"
                val intentString = """
                    | Final Intent:
                    | data = "$finalUrl"
                    | action = Intent.ACTION_VIEW
                    | flags = $flagString
                """.trimMargin()

                TextContent(intentString)

                // deeplink to target
                PaddedButton("Deeplink Away!", onClick = dropUnlessResumed {
                    val intent = Intent().apply {
                        data = finalUrl.toUri()
                        action = Intent.ACTION_VIEW
                        if (taskStack == TAG_NEW_TASK) {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    }

                    startActivity(intent)
                })
            }
        }
    }
}

private const val TAG_FIRST_NAME = "firstName"
private const val TAG_LOCATION = "location"
private const val TAG_TASK_STACK = "Task stack"
private const val TAG_CURRENT_TASK = "Use Current Task Stack"
private const val TAG_NEW_TASK = "Start New Task Stack"

private val MENU_OPTIONS_FIRST_NAME = mapOf(
    TAG_FIRST_NAME to LIST_FIRST_NAMES
)

private val MENU_OPTIONS_LOCATION = mapOf(
    TAG_LOCATION to LIST_LOCATIONS
)

private val MENU_OPTIONS_TASK_STACK = mapOf(
    TAG_TASK_STACK to listOf(TAG_CURRENT_TASK, TAG_NEW_TASK),
)

   
```