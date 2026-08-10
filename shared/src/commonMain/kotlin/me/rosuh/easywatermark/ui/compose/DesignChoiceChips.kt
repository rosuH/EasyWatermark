package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignChipSelected
import me.rosuh.easywatermark.ui.theme.DesignNeutralMuted

/**
 * Design-aligned choice chips for Style tab (tile mode, typeface, paint style).
 * Matches editor option chips: selected fill `#2C2C14` r=2, brand / muted labels — not Material
 * SegmentedButton.
 */
@Composable
fun <T> DesignChoiceChips(
    options: List<DesignChoiceOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * When true, each chip takes equal remaining width (form Text|Icon segment).
     * Default false keeps content-sized chips for Style typeface / tile rows.
     */
    equalWidth: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option.value == selected
            val bg = if (isSelected) DesignChipSelected else Color.Transparent
            val fg = when {
                !enabled -> DesignNeutralMuted.copy(alpha = 0.4f)
                isSelected -> DesignBrand
                else -> DesignNeutralMuted
            }
            Box(
                modifier = Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                    .widthIn(min = 56.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(bg)
                    .testTag("choice-$index")
                    .clickable(enabled = enabled) { onSelected(option.value) }
                    // I2: name + Radio role + selected/disabled (exclusive choice set).
                    .semantics {
                        contentDescription = option.label
                        this.selected = isSelected
                        role = Role.RadioButton
                        if (!enabled) disabled()
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = when {
                        option.fontWeight != FontWeight.Normal -> option.fontWeight
                        isSelected -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                    fontStyle = option.fontStyle,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

data class DesignChoiceOption<T>(
    val label: String,
    val value: T,
    val fontStyle: FontStyle = FontStyle.Normal,
    val fontWeight: FontWeight = FontWeight.Normal,
)
