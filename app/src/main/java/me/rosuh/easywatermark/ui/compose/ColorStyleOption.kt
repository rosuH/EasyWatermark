package me.rosuh.easywatermark.ui.compose

import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.Arc
import androidx.constraintlayout.compose.DebugFlags
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.compose.ExperimentalMotionApi
import androidx.constraintlayout.compose.MotionLayout
import androidx.constraintlayout.compose.MotionLayoutDebugFlags
import androidx.constraintlayout.compose.MotionScene
import androidx.constraintlayout.compose.RelativePosition
import androidx.constraintlayout.compose.layoutId
import kotlinx.coroutines.delay
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import java.util.EnumSet
import kotlin.math.abs

private val white by lazy {
    android.graphics.Color.WHITE
}

private val black by lazy {
    android.graphics.Color.BLACK
}

private val yellow by lazy {
    android.graphics.Color.parseColor("#FFB800")
}

private val orange by lazy {
    android.graphics.Color.parseColor("#FF3535")
}

private val pink by lazy {
    android.graphics.Color.parseColor("#FF008A")
}

private val blue by lazy {
    android.graphics.Color.parseColor("#00D1FF")
}

private val green by lazy {
    android.graphics.Color.parseColor("#1BFF3F")
}

private val customPicker by lazy {
    -1
}

private val defaultColorList by lazy {
    listOf(
        ColorItem(white, description = "white"),
        ColorItem(black, description = "black"),
        ColorItem(yellow, description = "yellow"),
        ColorItem(orange, description = "orange"),
        ColorItem(pink, description = "pink"),
        ColorItem(blue, description = "blue"),
        ColorItem(green, description = "green"),
        ColorItem(
            customPicker,
            description = "color picker",
            isIcon = true,
            iconResId = R.drawable.ic_btn_color_picker
        )
    )
}

@Preview
@Composable
private fun ColorOptionPreview() {
    val waterMark = WaterMark(
        text = "\uD83D\uDC4B DO NOT REDISTRIBUTE",
        textSize = (14f).coerceAtLeast(1f),
        textColor = android.graphics.Color.parseColor("#FFB800"),
        textStyle = TextPaintStyle.obtainSealedClass(0),
        textTypeface = TextTypeface.obtainSealedClass(0),
        alpha = 255,
        degree = 315f,
        hGap = 0,
        vGap = 0,
        iconUri = Uri.parse(""),
        markMode = WaterMarkRepository.MarkMode.Text,
        enableBounds = false,
        tileMode = Shader.TileMode.CLAMP,
    )
    ColorOption(
        item = FuncTitleModel(
            FuncTitleModel.FuncType.Color,
            R.string.title_text_color,
            R.drawable.ic_func_color
        ), waterMark = waterMark
    )
}

private data class ColorItem(
    val color: Int,
    val selected: Boolean = false,
    val description: String = color.toString(),
    val isIcon: Boolean = false,
    val iconResId: Int = 0,
)

@OptIn(ExperimentalAnimationApi::class, ExperimentalMotionApi::class)
@Composable
fun ColorOption(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
) {
    val selectedColor = (defaultColorList.find { it.color == waterMark.textColor } ?: ColorItem(
        waterMark.textColor,
        description = "color picker",
        isIcon = true,
        iconResId = R.drawable.ic_btn_color_picker
    )).copy(
        selected = true
    )
    val unSelectedColorList = defaultColorList.filter { it.color != selectedColor.color }
    val jsonScene =
        """
            {
            Variables: {
                    angle: {
                      from: 0,
                      step: 51,
                    },
                    distance: 100,
                    angle2: {
                      from: 51,
                      step: 51,
                    },
                    distance2: 70,
                    mylist: {
                      tag: 'box',
                    },
                  },
              ConstraintSets: {
                start: {
                  centerCircle: {
                    width: 5,
                    height: 5,
                    center: 'parent',
                  },
                  Generate: {
                    mylist: {
                      width: 10,
                      height: 10,
                      circular: [
                        'parent',
                        'angle',
                        'distance',
                      ],
                    },
                  },
                },
                end: {
                  centerCircle: {
                    width: 70,
                    height: 70,
                    center: 'parent',
                  },
                  Generate: {
                    mylist: {
                      width: 10,
                      height: 10,
                      circular: [
                        'parent',
                        'angle2',
                        'distance2',
                      ],
                    },
                  },
                },
              },
              Transitions: {           
                  default: {            
                    from: 'start',      
                    to: 'end',          
                    KeyFrames: {        
                      KeyAttributes: [  
                        {target: ['h1'], frames: [0,17,34,51,68,85,100], scaleX: [0,0.5,1,0.5,0,0,0], scaleY: [0,0.5,1,0.5,0,0,0]},
                        {target: ['h2'], frames: [0,17,34,51,68,85,100], scaleX: [0,0,0.5,1,0.5,0,0], scaleY: [0,0,0.5,1,0.5,0,0]},
                        {target: ['h3'], frames: [0,17,34,51,68,85,100], scaleX: [0,0,0,0.5,1,0.5,0], scaleY: [0,0,0,0.5,1,0.5,0]},
                      ]
                    }
                  }
                }
            }
        """.trimIndent()

    val animateToEnd by remember { mutableStateOf(true) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(animateToEnd) {
        delay(50)
        progress.animateTo(
            if (animateToEnd) 1f else 0f,
            animationSpec = tween(5000)
        )
    }


    MotionLayout(
        motionScene = MotionScene(jsonScene),
        modifier = Modifier.fillMaxSize(),
        progress = progress.value,
        debugFlags = DebugFlags.All,
    ) {
        unSelectedColorList.forEachIndexed { index, colorItem ->
            ColorItemComponent(
                colorItem = colorItem,
                modifier = Modifier
                    .layoutId("id${index}", "box")
                    .clip(CircleShape),
                onChange = onChange,
                item = item
            )
        }

        ColorItemComponent(
            colorItem = selectedColor,
            modifier = Modifier
                .border(
                    width = 3.dp,
                    color = Color(white),
                    shape = CircleShape
                )
                .layoutId("centerCircle")
                .clip(CircleShape),
            onChange = onChange,
            item = item
        )
    }
}

@Composable
private fun ColorItemComponent(
    colorItem: ColorItem,
    modifier: Modifier = Modifier,
    onChange: (item: FuncTitleModel, any: Any) -> Unit,
    item: FuncTitleModel,
) {
    Image(
        painter = if (colorItem.isIcon) {
            painterResource(id = R.drawable.ic_btn_color_picker)
        } else {
            ColorPainter(Color(colorItem.color))
        },
        contentDescription = "white",
        modifier = modifier.clickable {
            onChange(item, colorItem.color)
        },
    )
}