# LocalLifecycleOwner Recipe

This recipe demonstrates how to use `LifecycleResumeEffect` in Navigation 3 entries to pause and resume work based on the entry's lifecycle state.

## How it works

In Navigation 3, by default each `NavEntry` is provided its own `LifecycleOwner` via `LocalLifecycleOwner.current`. This means that any lifecycle-aware components inside the entry is automatically scoped to the `NavEntry`.

### `LifecycleResumeEffect` with Dialog Scenes

1. **RouteA (Screen)**:

   - Uses `LifecycleResumeEffect(Unit)` scoped to the `NavEntry`'s `LocalLifecycleOwner.current` to advance the`LinearProgressIndicator` while in the `RESUMED` state.
   - Automatically resets `progressValue` back to `0f` whenever it hits `1f`.
2. **RouteB (Dialog)**:

   - Configured as a dialog using `DialogSceneStrategy.dialog()`.
   - When the user opens the RouteB dialog, RouteA remains visible behind the dialog in the `STARTED` state (leaving `RESUMED`).
   - `LifecycleResumeEffect` calls `onPauseOrDispose`, pausing the progress indicator.
   - When the dialog is dismissed, RouteA returns to `RESUMED`, and `LifecycleResumeEffect` resumes the progress indicator automatically.

[![](https://developer.android.com/static/images/picto-icons/code.svg) Explore View the full recipe on GitHub.](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/lifecycleowner)

```
/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.nav3recipes.lifecycleowner

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.example.nav3recipes.content.ContentGreen
import com.example.nav3recipes.ui.setEdgeToEdgeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data object RouteA : NavKey

@Serializable
private data object RouteB : NavKey

class LifecycleOwnerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setEdgeToEdgeConfig()
        super.onCreate(savedInstanceState)
        setContent {
            val backStack = rememberNavBackStack(RouteA)
            val dialogStrategy = remember { DialogSceneStrategy<NavKey>() }

            NavDisplay(
                backStack = backStack,
                onBack = backStack::removeLastOrNull,
                sceneStrategies = listOf(dialogStrategy),
                entryProvider = entryProvider {
                    entry<RouteA> {
                        LogLifecycleEffect("ScreenA")
                        ContentGreen("Screen A") {
                            var progressValue by remember { mutableFloatStateOf(0f) }
                            val coroutineScope = rememberCoroutineScope()

                            val animatedProgress by animateFloatAsState(
                                targetValue = progressValue,
                                animationSpec = if (progressValue == 0f) snap() else tween(durationMillis = 100, easing = LinearEasing),
                                label = "ProgressAnimation"
                            )

                            // LifecycleResumeEffect runs only while ScreenA is in the RESUMED state.
                            // When the dialog opens, ScreenA transitions to PAUSED, which calls onPauseOrDispose.
                            LifecycleResumeEffect(Unit) {
                                val job = coroutineScope.launch {
                                    while (true) {
                                        delay(100.milliseconds)
                                        progressValue += 0.01f
                                        if (progressValue >= 1f) {
                                            progressValue = 0f
                                        }
                                    }
                                }
                                onPauseOrDispose {
                                    job.cancel()
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                LinearProgressIndicator(progress = { animatedProgress })
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = dropUnlessResumed { backStack.add(RouteB) }) {
                                    Text("Open Dialog")
                                }
                            }
                        }
                    }
                    entry<RouteB>(metadata = DialogSceneStrategy.dialog()) {
                        LogLifecycleEffect("Dialog")
                        AlertDialog(
                            onDismissRequest = backStack::removeLastOrNull,
                            title = { Text("Dialog") },
                            text = {
                                Text("Screen A is no longer in resumed state, pausing the progress indicator.")
                            },
                            confirmButton = {
                                Button(onClick = dropUnlessResumed { backStack.removeLastOrNull() }) {
                                    Text("Dismiss Dialog")
                                }
                            }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun LogLifecycleEffect(screenName: String) {
    LifecycleEventEffect(Lifecycle.Event.ON_CREATE) {
        Log.d("LifecycleOwner", "$screenName: ON_CREATE")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        Log.d("LifecycleOwner", "$screenName: ON_START")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Log.d("LifecycleOwner", "$screenName: ON_RESUME")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        Log.d("LifecycleOwner", "$screenName: ON_PAUSE")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        Log.d("LifecycleOwner", "$screenName: ON_STOP")
    }
    // Note that ON_DESTROY event is not observable from composables.
}
```