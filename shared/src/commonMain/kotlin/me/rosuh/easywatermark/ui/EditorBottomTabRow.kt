package me.rosuh.easywatermark.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomTabRow(
    selectedTabIndex: Int,
    labels: List<String>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onIndicatorPosition: (startPx: Int, endPx: Int) -> Unit = { _, _ -> },
) {
    HorizontalDivider(thickness = 0.5.dp, color = DividerDefaults.color.copy(alpha = 0.5f))
    PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        indicator = {
            val indicatorHeight = 3.dp
            val coroutineScope = rememberCoroutineScope()
            var widthAnimatable by remember {
                mutableStateOf<Animatable<Dp, AnimationVector1D>?>(
                    null
                )
            }
            var offsetXStartAnimatable by remember {
                mutableStateOf<Animatable<Dp, AnimationVector1D>?>(null)
            }
            var offsetXEndAnimatable by remember {
                mutableStateOf<Animatable<Dp, AnimationVector1D>?>(null)
            }
            val density = LocalDensity.current
            val primaryColor = MaterialTheme.colorScheme.primary
            Box(Modifier.tabIndicatorLayout {
                measurable: Measurable,
                constraints: Constraints,
                tabPositions: List<TabPosition>, ->
                val contentWidth = tabPositions[selectedTabIndex].contentWidth
                val widthAnimate = widthAnimatable ?: Animatable<Dp, AnimationVector1D>(
                    contentWidth,
                    Dp.VectorConverter
                ).also {
                    widthAnimatable = it
                }
                val width = widthAnimate.value
                if (width != widthAnimate.value) {
                    coroutineScope.launch {
                        widthAnimate.animateTo(
                            contentWidth,
                            animationSpec =
                                // Handle directionality here, if we are moving to the right, we
                                // want the right side of the indicator to move faster, if we are
                                // moving to the left, we want the left side to move faster.
                                if (widthAnimate.targetValue < contentWidth) {
                                    spring(dampingRatio = 1f, stiffness = 50f)
                                } else {
                                    spring(dampingRatio = 1f, stiffness = 1000f)
                                }
                        )
                    }
                }
                val newStart = tabPositions[selectedTabIndex].left
                val newEnd = tabPositions[selectedTabIndex].right
                val offsetXStartAnimate = offsetXStartAnimatable ?: Animatable<Dp, AnimationVector1D>(
                    newStart,
                    Dp.VectorConverter
                ).also {
                    offsetXStartAnimatable = it
                }
                val offsetXEndAnimate = offsetXEndAnimatable ?: Animatable<Dp, AnimationVector1D>(
                    newEnd,
                    Dp.VectorConverter
                ).also {
                    offsetXEndAnimatable = it
                }

                if (offsetXStartAnimate.targetValue != newStart) {
                    coroutineScope.launch {
                        offsetXStartAnimate.animateTo(
                            newStart,
                            animationSpec = if (offsetXStartAnimate.targetValue < newStart) {
                                spring(dampingRatio = 1f, stiffness = 1000f)
                            } else {
                                spring(dampingRatio = 1f, stiffness = 200f)
                            }
                        )
                    }
                }
                if (offsetXEndAnimate.targetValue != newEnd) {
                    coroutineScope.launch {
                        offsetXEndAnimate.animateTo(
                            newEnd,
                            animationSpec = if (offsetXEndAnimate.targetValue < newEnd) {
                                spring(dampingRatio = 1f, stiffness = 200f)
                            } else {
                                spring(dampingRatio = 1f, stiffness = 1000f)
                            }
                        )
                    }
                }
                val offsetXStart = offsetXStartAnimate.value.roundToPx()
                val offsetXEnd = offsetXEndAnimate.value.roundToPx()
                onIndicatorPosition(offsetXStart, offsetXEnd)
                val placeable = measurable.measure(constraints.copy(
                    minWidth = (offsetXEnd - offsetXStart).absoluteValue,
                    maxWidth = (offsetXEnd - offsetXStart).absoluteValue,
                    minHeight = constraints.maxHeight,
                    maxHeight = constraints.maxHeight
                ))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        offsetXStart,
                        0
                    )
                }
            }.drawWithContent {
                drawContent()
                drawRoundRect(
                    color = primaryColor,
                    size = size.copy(
                        width = (widthAnimatable?.value ?: 0.dp).roundToPx().toFloat(),
                        height = indicatorHeight.roundToPx().toFloat()
                    ),
                    topLeft = Offset(
                        x = (size.width - (widthAnimatable?.value ?: 0.dp).roundToPx()) / 2f,
                        y = size.height - indicatorHeight.roundToPx().toFloat()
                    ),
                    cornerRadius = CornerRadius(
                        indicatorHeight.roundToPx().toFloat() / 2f,
                        indicatorHeight.roundToPx().toFloat() / 2f
                    )
                )
            })
        },
        divider = {},
        modifier = modifier.fillMaxWidth()
    ) {
        val textModifier = Modifier
            .fillMaxHeight()
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = {
                    onTabSelected(index)
                },
                modifier = if (index == 0) Modifier.height(48.dp) else Modifier,
            ) {
                Column(modifier = textModifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        text = label,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}
