# Conditional Transitions Recipe

This recipe demonstrates how to create route-dependent screen transitions in Navigation 3 using `transitionSpec` and `popTransitionSpec`. The slide directions (right, left, bottom, top) are conditionally selected based on pairs of `initialState` and `targetState` navigation keys.

## How it works

- **Route Definitions** : Navigation destinations (`Step1`, `Step2`, `Step3`, `Step4`) are defined using a sealed class hierarchy implementing `NavKey` and marked with `@Serializable`.
- **Conditional Forward Transitions (`transitionSpec`)** : Matches pairs of `(initialKey to targetKey)` to determine the direction of the slide animation:
  - `Step1` $\\rightarrow$ `Step2`: Swipes to the left
  - `Step2` $\\rightarrow$ `Step3`: Swipes to the up
  - `Step3` $\\rightarrow$ `Step4`: Swipes to the right
  - `Step4` $\\rightarrow$ `Step1`: Slides to the bottom (restarts flow)
- **Conditional Pop Transitions (`popTransitionSpec`)**: Handles reverse slide directions when navigating back or when clearing the backstack.
- **Backstack Control** : Demonstrates clearing the navigation stack on the final step (`backStack.clear()` \& `backStack.add(Step1)`) while executing a seamless top-slide transition.

[![](https://developer.android.com/static/images/picto-icons/code.svg) Explore View the full recipe on GitHub.](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/conditionaltransitions)

```
package com.example.nav3recipes.conditionaltransitions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.nav3recipes.content.ContentGreen
import com.example.nav3recipes.content.ContentOrange
import com.example.nav3recipes.content.ContentPurple
import com.example.nav3recipes.content.ContentRed
import com.example.nav3recipes.sharedviewmodel.toContentKey
import com.example.nav3recipes.ui.setEdgeToEdgeConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable

@Serializable
sealed class Step(val firstButtonTitle: String, val secondButtonTitle: String) : NavKey

@Serializable
data object Step1 : Step("Home", "Swipe left")

@Serializable
data object Step2 : Step("Swipe right", "Swipe up")

@Serializable
data object Step3 : Step("Swipe down", "Swipe right")

@Serializable
data object Step4 : Step("Swipe left", "Swipe down")

@AndroidEntryPoint
class ConditionalTransitionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setEdgeToEdgeConfig()

        setContent {
            Scaffold { paddingValues ->
                val backStack = rememberNavBackStack(Step1)

                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.padding(paddingValues),
                    onBack = backStack::removeLastOrNull,
                    entryProvider = entryProvider {
                        entry<Step1> {
                            ContentGreen(title = "Screen 1") {
                                StepContent(
                                    step = it,
                                    onNext = { backStack += Step2 },
                                    onBack = ::finish, // closing the activity
                                )
                            }

                        }
                        entry<Step2> {
                            ContentRed(title = "Screen 2") {
                                StepContent(
                                    step = it,
                                    onNext = { backStack += Step3 },
                                    onBack = backStack::removeLastOrNull,
                                )
                            }
                        }
                        entry<Step3> {
                            ContentOrange(title = "Screen 3") {
                                StepContent(
                                    step = it,
                                    onNext = { backStack += Step4 },
                                    onBack = backStack::removeLastOrNull,
                                )
                            }
                        }
                        entry<Step4> {
                            ContentPurple(title = "Screen 4") {
                                StepContent(
                                    step = it,
                                    onNext = {
                                        backStack.clear()
                                        backStack.add(Step1)
                                    },
                                    onBack = backStack::removeLastOrNull,
                                )
                            }
                        }
                    },
                    transitionSpec = {
                        val initialKey = initialState.entries.lastOrNull()?.contentKey
                        val targetKey = targetState.entries.lastOrNull()?.contentKey

                        when (initialKey to targetKey) {
                            Step1.toContentKey() to Step2.toContentKey() -> swipeLeft()
                            Step2.toContentKey() to Step3.toContentKey() -> swipeUp()
                            Step3.toContentKey() to Step4.toContentKey() -> swipeRight()
                            Step4.toContentKey() to Step1.toContentKey() -> swipeDown()
                            else -> swipeRight()
                        }
                    },
                    popTransitionSpec = {
                        val initialKey = initialState.entries.lastOrNull()?.contentKey
                        val targetKey = targetState.entries.lastOrNull()?.contentKey

                        when (initialKey to targetKey) {
                            Step4.toContentKey() to Step1.toContentKey() -> swipeDown() // via backstack clearing
                            Step2.toContentKey() to Step1.toContentKey() -> swipeRight()
                            Step3.toContentKey() to Step2.toContentKey() -> swipeDown()
                            Step4.toContentKey() to Step3.toContentKey() -> swipeLeft()
                            else -> swipeLeft()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun StepContent(step: Step, onNext: () -> Unit, onBack: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ElevatedButton(onBack) { Text(text = step.firstButtonTitle) }
            ElevatedButton(onNext) { Text(text = step.secondButtonTitle) }
        }
    }
}

private fun swipeLeft(): ContentTransform =
    slideInHorizontally(initialOffsetX = { it }) togetherWith slideOutHorizontally(targetOffsetX = { -it })

private fun swipeUp(): ContentTransform =
    slideInVertically(initialOffsetY = { it }) togetherWith slideOutVertically(targetOffsetY = { -it })

private fun swipeRight(): ContentTransform =
    slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })

private fun swipeDown(): ContentTransform =
    slideInVertically(initialOffsetY = { -it }) togetherWith slideOutVertically(targetOffsetY = { it })
```