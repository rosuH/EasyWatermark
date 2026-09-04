package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.ui.theme.DesignNeutralMuted

/**
 * Function option: icon 24 + label 12 semibold.
 * Selected → brand (primary); unselected → white @ 50% (design neutral_2).
 */
@Composable
fun EditorOptionItem(
    icon: Painter,
    contentDescription: String,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        DesignNeutralMuted
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.height(24.dp),
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
