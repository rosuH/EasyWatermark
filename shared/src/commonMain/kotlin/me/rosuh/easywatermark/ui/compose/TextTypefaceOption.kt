package me.rosuh.easywatermark.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.text_typeface_bold
import me.rosuh.easywatermark.shared.generated.resources.text_typeface_bold_italic
import me.rosuh.easywatermark.shared.generated.resources.text_typeface_italic
import me.rosuh.easywatermark.shared.generated.resources.text_typeface_normal
import org.jetbrains.compose.resources.stringResource

/** S-i18n-2: labels from composeResources. Design chips (not Material SegmentedButton). */
@Composable
fun TextTypeface(
    typeface: TextTypeface,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (TextTypeface) -> Unit,
) {
    DesignChoiceChips(
        options = listOf(
            DesignChoiceOption(
                label = stringResource(Res.string.text_typeface_normal),
                value = TextTypeface.Normal,
            ),
            DesignChoiceOption(
                label = stringResource(Res.string.text_typeface_bold),
                value = TextTypeface.Bold,
                fontWeight = FontWeight.Bold,
            ),
            DesignChoiceOption(
                label = stringResource(Res.string.text_typeface_italic),
                value = TextTypeface.Italic,
                fontStyle = FontStyle.Italic,
            ),
            DesignChoiceOption(
                label = stringResource(Res.string.text_typeface_bold_italic),
                value = TextTypeface.BoldItalic,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
            ),
        ),
        selected = typeface,
        onSelected = onValueChange,
        modifier = modifier,
        enabled = enabled,
    )
}
