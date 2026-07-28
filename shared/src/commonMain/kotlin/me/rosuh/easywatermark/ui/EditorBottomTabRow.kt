package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.DesignNeutralMuted

/**
 * Design tabs (Figma preview_edit):
 * - indicator **2dp** brand bar under label (Material [PrimaryIndicator])
 * - selected text brand + semibold; unselected white@50%
 * - no hairline divider (seamless olive editor bg)
 *
 * Uses the stock [TabRowDefaults.PrimaryIndicator] + [tabIndicatorOffset] path instead of a
 * Hand-rolled measure/animate layout — the custom measure path could throw on iOS when * tabPositions was empty or width resolved to 0 during tab switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomTabRow(
    selectedTabIndex: Int,
    labels: List<String>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onIndicatorPosition: (startPx: Int, endPx: Int) -> Unit = { _, _ -> },
) {
    val brand = MaterialTheme.colorScheme.primary.takeIf {
        it != Color.Unspecified
    } ?: DesignBrand
    val container = MaterialTheme.colorScheme.background.takeIf {
        it != Color.Unspecified
    } ?: DesignEditorBg
    // Clamp for safety if caller ever passes an out-of-range index during a tab list swap.
    val safeIndex = selectedTabIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))

    PrimaryTabRow(
        selectedTabIndex = safeIndex,
        containerColor = container,
        contentColor = brand,
        indicator = {
            if (labels.isNotEmpty() && safeIndex in labels.indices) {
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(
                        selectedTabIndex = safeIndex,
                        matchContentSize = true,
                    ),
                    width = androidx.compose.ui.unit.Dp.Unspecified,
                    height = 2.dp,
                    color = brand,
                )
            }
        },
        divider = {},
        modifier = modifier.fillMaxWidth(),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = safeIndex == index
            Tab(
                selected = selected,
                onClick = {
                    // Keep legacy callback signature; hosts may still use indicator position.
                    onIndicatorPosition(0, 0)
                    onTabSelected(index)
                },
                selectedContentColor = brand,
                unselectedContentColor = DesignNeutralMuted,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("editorTab-$index"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) brand else DesignNeutralMuted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
