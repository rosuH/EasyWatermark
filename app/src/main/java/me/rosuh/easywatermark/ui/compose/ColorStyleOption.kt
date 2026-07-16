package me.rosuh.easywatermark.ui.compose

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_choose_color_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.ui.SharedProductDrawables
import org.jetbrains.compose.resources.stringResource

/**
 * Android Style → Color control. Layout matches production v2.10.0 [ColorFragment]:
 * horizontal preset swatches + trailing custom picker that opens skydoves [ColorPickerDialog].
 *
 * The previous Compose WIP used MotionLayout with [DebugFlags.All], which painted a huge green
 * oval over the control surface — not product UI.
 */
private val white = AndroidColor.WHITE
private val black = AndroidColor.BLACK
private val yellow = AndroidColor.parseColor("#FFB800")
private val orange = AndroidColor.parseColor("#FF3535")
private val pink = AndroidColor.parseColor("#FF008A")
private val blue = AndroidColor.parseColor("#00D1FF")
private val green = AndroidColor.parseColor("#1BFF3F")

private data class ColorItem(
    val color: Int = white,
    val isCustomPicker: Boolean = false,
    val description: String = "",
)

private val presetColorList: List<ColorItem> = listOf(
    ColorItem(white, description = "white"),
    ColorItem(black, description = "black"),
    ColorItem(yellow, description = "yellow"),
    ColorItem(orange, description = "orange"),
    ColorItem(pink, description = "pink"),
    ColorItem(blue, description = "blue"),
    ColorItem(green, description = "green"),
    ColorItem(isCustomPicker = true, description = "color picker"),
)

@Preview
@Composable
private fun ColorOptionPreview() {
    val waterMark = WaterMark(
        text = "\uD83D\uDC4B DO NOT REDISTRIBUTE",
        textSize = 14f.coerceAtLeast(1f),
        textColor = yellow,
        textStyle = TextPaintStyle.obtainSealedClass(0),
        textTypeface = TextTypeface.obtainSealedClass(0),
        alpha = 255,
        degree = 315f,
        hGap = 0,
        vGap = 0,
        iconUri = MediaRef.Empty,
        markMode = WatermarkMode.Text,
        enableBounds = false,
        tileMode = WatermarkTileMode.CLAMP,
    )
    ColorOption(
        item = FuncTitleModel(FuncType.Color),
        waterMark = waterMark,
    )
}

@Composable
fun ColorOption(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val chooseColorTitle = stringResource(Res.string.tips_choose_color_dialog)
    val confirmLabel = stringResource(Res.string.tips_confirm_dialog)
    val cancelLabel = stringResource(Res.string.tips_cancel_dialog)

    val selectedIsPreset = presetColorList.any {
        !it.isCustomPicker && it.color == waterMark.textColor
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = presetColorList,
            key = { index, colorItem ->
                if (colorItem.isCustomPicker) "picker" else "c_${colorItem.color}_$index"
            },
        ) { _, colorItem ->
            val selected = if (colorItem.isCustomPicker) {
                !selectedIsPreset
            } else {
                colorItem.color == waterMark.textColor
            }
            ColorSwatch(
                colorItem = colorItem,
                selected = selected,
                onClick = {
                    if (colorItem.isCustomPicker) {
                        ColorPickerDialog.Builder(context)
                            .setTitle(chooseColorTitle)
                            .setPreferenceName(SP_COLOR_PICKER_DIALOG)
                            .setPositiveButton(
                                confirmLabel,
                                object : ColorEnvelopeListener {
                                    override fun onColorSelected(
                                        envelope: ColorEnvelope?,
                                        fromUser: Boolean,
                                    ) {
                                        envelope?.color?.let { picked ->
                                            // Production passes full ARGB (alpha included).
                                            onChange(item, picked)
                                        }
                                    }
                                },
                            )
                            .setNegativeButton(cancelLabel) { dialog, _ -> dialog.dismiss() }
                            .attachAlphaSlideBar(true)
                            .attachBrightnessSlideBar(true)
                            .setBottomSpace(20)
                            .show()
                    } else {
                        onChange(item, colorItem.color)
                    }
                },
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    colorItem: ColorItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Production item_color_preview: 30dp outer, ~24dp fill circle.
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(width = 2.dp, color = Color.White, shape = CircleShape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                },
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = colorItem.description },
        contentAlignment = Alignment.Center,
    ) {
        if (colorItem.isCustomPicker) {
            Image(
                painter = SharedProductDrawables.colorPickerPainter(),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
            )
        } else {
            Image(
                painter = ColorPainter(Color(colorItem.color)),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
            )
        }
    }
}

private const val SP_COLOR_PICKER_DIALOG = "water_mark_color_picker_dialog"
