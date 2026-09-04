package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

data class EditorBottomControlTab<T>(
    val label: String,
    val options: List<T>,
    val useCompactPadding: Boolean = false,
)

/**
 * Shared CMP bottom-controls host for the editor.
 *
 * Platform callers still provide resource-backed option models and option bodies; this shell owns
 * Only tab/option selection state and the shared carousel/tab layout wiring. *
 * [optionControl] receives [optionActivationSignal]: bumps only when
 * [shouldSignalActivation] is true for the tapped option (Text opens its sheet; Icon must
 * **not** bump or the exiting Text panel would flash the edit sheet during AnimatedContent).
 *
 * Option panel height is **fixed** so switching tools does not push the preview up/down.
 * Tool switches use production fragment-style transition: enter slide-up 60% + fade, exit fade
 * (`fragment_open_in` / `fragment_pop_exit_slide`), with durations scaled by [currentMotionPolicy].
 */
@Composable
fun <T> EditorBottomControlsShell(
    tabs: List<EditorBottomControlTab<T>>,
    modifier: Modifier = Modifier,
    optionControl: @Composable (option: T, modifier: Modifier, optionActivationSignal: Int) -> Unit,
    optionItem: @Composable (option: T, selected: Boolean) -> Unit,
    /**
 * When true, [optionActivationSignal] increments (Text sheet open). Default false so
 * Icon/Style tools never open the text dialog as a side effect of selection.
     */
    shouldSignalActivation: (T) -> Boolean = { false },
    onIndicatorPosition: (startPx: Int, endPx: Int) -> Unit = { _, _ -> },
    initialTabIndex: Int = 0,
    initialOptionIndex: Int = 0,
) {
    if (tabs.isEmpty()) {
        return
    }

    val motionPolicy = currentMotionPolicy()
    val slideMs = motionDurationMs(motionPolicy, EwmTheme.motion.optionPanelSlideMs)
    val fadeMs = motionDurationMs(motionPolicy, EwmTheme.motion.optionPanelFadeMs)

    var selectedTabIndex by remember { mutableIntStateOf(initialTabIndex) }
    val safeTabIndex = selectedTabIndex.coerceIn(tabs.indices)
    val selectedTab = tabs[safeTabIndex]
    var selectedOption by remember(safeTabIndex, selectedTab.options) {
        mutableStateOf(
            selectedTab.options.getOrNull(initialOptionIndex) ?: selectedTab.options.firstOrNull(),
        )
    }
    LaunchedEffect(initialTabIndex, initialOptionIndex) {
        selectedTabIndex = initialTabIndex.coerceIn(tabs.indices)
        val tab = tabs[selectedTabIndex.coerceIn(tabs.indices)]
        selectedOption = tab.options.getOrNull(initialOptionIndex) ?: tab.options.firstOrNull()
    }
    // 0 = no user activation yet (default selection alone must not open the text sheet).
    // Reset on tab change so switching back to Content does not auto-open the text sheet.
    var optionActivationSignal by remember(safeTabIndex) { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Fixed slot: prevents preview reflow when control body height differs (slider vs palette).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OptionControlPanelHeight),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = selectedOption,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    // Production: open_in = translate 60%→0 (medium) + fade (short);
                    // pop_exit = fade only (short). Full-motion defaults 300/200; Off → 0ms.
                    val enter = slideInVertically(
                        animationSpec = tween(slideMs, easing = FastOutSlowInEasing),
                        initialOffsetY = { fullHeight -> (fullHeight * 0.6f).toInt() },
                    ) + fadeIn(
                        animationSpec = tween(fadeMs, easing = FastOutSlowInEasing),
                    )
                    val exit = fadeOut(
                        animationSpec = tween(fadeMs, easing = FastOutSlowInEasing),
                    )
                    enter togetherWith exit
                },
                label = "editorOptionControl",
            ) { option ->
                if (option != null) {
                    optionControl(
                        option,
                        Modifier.fillMaxWidth(),
                        optionActivationSignal,
                    )
                }
            }
        }
        // Reset LazyRow + scroll state per tab so Content→Style does not run insert/placement
        // animations across unrelated catalogs (chip label ghosting when flinging immediately).
        key(safeTabIndex) {
            EditorOptionCarousel(
                options = selectedTab.options,
                selectedOption = selectedOption,
                useCompactPadding = selectedTab.useCompactPadding,
                // Lazy keys on Android MUST be Bundle-storable (String/Int/…).
                // Passing FuncType object instances crashes:
                // IllegalArgumentException: Type of the key … is not supported.
                itemKey = { option ->
                    when (option) {
                        is EditorOptionSpec -> "opt_${option.type.stableKey()}"
                        is FuncType -> "ft_${option.stableKey()}"
                        else -> option.toString()
                    }
                },
                itemTestTag = { option ->
                    val stableKey = when (option) {
                        is EditorOptionSpec -> option.type.stableKey()
                        is FuncType -> option.stableKey()
                        else -> option.toString()
                    }
                    "editorOption-$stableKey"
                },
                onOptionSelected = {
                    selectedOption = it
                    if (shouldSignalActivation(it)) {
                        optionActivationSignal += 1
                    }
                },
                itemContent = optionItem,
            )
        }

        EditorBottomTabRow(
            selectedTabIndex = safeTabIndex,
            labels = tabs.map { it.label },
            onTabSelected = { index ->
                if (index in tabs.indices) {
                    selectedTabIndex = index
                }
            },
            onIndicatorPosition = onIndicatorPosition,
        )
    }
}

/** Fixed height for the active tool panel (slider+value row / segments / swatches). */
internal val OptionControlPanelHeight = 64.dp
