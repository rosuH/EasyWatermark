package me.rosuh.easywatermark.ui

import android.util.Log
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.compose.ColorOption
import me.rosuh.easywatermark.ui.compose.IconOption
import me.rosuh.easywatermark.ui.compose.SliderOption
import me.rosuh.easywatermark.ui.compose.TextContentOption
import me.rosuh.easywatermark.ui.compose.TextTypeface
import me.rosuh.easywatermark.ui.compose.TileMode
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
import kotlin.math.absoluteValue


@Composable
fun EditorScreen(
    imageList: List<ImageInfo>,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    selectedImage: ImageInfo? = null,
    onBack: () -> Unit,
    onImageSelected: (ImageInfo) -> Unit = {},
    onImageDelete: () -> Unit = {},
    onWaterMrkChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
    onAddMoreImages: () -> Unit = { },
    onShowSaveDialog: () -> Unit = { },
    onGoAboutScreen: () -> Unit = { },
) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // topBar
        EditorTopBar(
            Modifier.fillMaxWidth(),
            onBack = onBack,
            onAddMoreImages = onAddMoreImages,
            onShowSaveDialog = onShowSaveDialog,
            onGoAboutScreen = onGoAboutScreen
        )
        // WaterMarkView
        WaterMarkView(
            Modifier.weight(1f, true),
            waterMark,
            selectedImage ?: imageList.firstOrNull()
        )
        // PreviewList
        if (imageList.size > 1) {
            PhotoList(
                imageList,
                selectedImage,
                modifier = Modifier.fillMaxWidth(),
                onImageSelected,
                onImageDelete
            )
        }
        BottomView(waterMark, onChange = onWaterMrkChange)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BottomView(
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
) {
    // StylePreview
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val optionList = remember(selectedTabIndex) { mutableStateListOf(*(when (selectedTabIndex) {
            0 -> {
                contentFunList
            }

            1 -> {
                styleFunList
            }

            2 -> {
                layoutFunList
            }

            else -> {
                throw IllegalStateException("Unexpected value: $selectedTabIndex")
            }
        }).toTypedArray())
    }
    var selectedOption by remember(selectedTabIndex) { mutableStateOf(optionList.first()) }
    var showOptionControl by remember(selectedTabIndex) { mutableStateOf(selectedTabIndex != 0) }
    var optionWidth by remember {
        mutableStateOf(0.dp)
    }
    var bottomViewHeight by remember {
        mutableStateOf(0.dp)
    }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                bottomViewHeight = with(density) {
                    it.size.height.toDp()
                }
            }
    ) {
        OptionControl(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            item = selectedOption,
            waterMark = waterMark,
            showSheet = showOptionControl,
            onChange = onChange,
            onDismissRequest = { showOptionControl = false }
        )
        val itemWidth = 72.dp
        val contentPadding = if (selectedTabIndex == 1) {
            8.dp
        } else {
            (optionWidth - itemWidth).coerceAtLeast(0.dp) / 2
        }
        LazyRow(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .onGloballyPositioned {
                    optionWidth = with(density) {
                        it.size.width.toDp()
                    }
                },
            state = listState,
            contentPadding = PaddingValues(
                start = contentPadding,
                end = contentPadding,
            ),
        ) {
            itemsIndexed(optionList) { index, item ->
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clickable {
                            selectedOption = item
                            showOptionControl = true
                        }
                        .animateItem()
                ) {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = stringResource(id = item.title),
                        modifier = Modifier.height(24.dp)
                    )
                    Text(
                        text = stringResource(id = item.title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Bottom Tab for contents, styles and layouts

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
                    Log.i("TabRow", "indicator: $offsetXStart - $offsetXEnd")
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
            modifier = Modifier.fillMaxWidth()
        ) {
            val textModifier = Modifier
                .fillMaxHeight()
            Tab(
                selectedTabIndex == 0,
                onClick = {
                    selectedTabIndex = 0
                },
                modifier = Modifier.height(48.dp)
            ) {
                Column(modifier = textModifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        text = stringResource(id = R.string.title_content),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            Tab(selectedTabIndex == 1, onClick = {
                selectedTabIndex = 1
            }) {
                Column(modifier = textModifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        text = stringResource(id = R.string.title_style),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            Tab(selectedTabIndex == 2, onClick = {
                selectedTabIndex = 2
            }) {
                Column(modifier = textModifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        text = stringResource(id = R.string.title_layout),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionControl(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    showSheet: Boolean = true,
    onChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
    onDismissRequest: () -> Unit,
) {
    if (showSheet) {
        val configuration = LocalWindowInfo.current.containerSize
        val screenHeight = configuration.height.dp
        val isColor = item.type == FuncTitleModel.FuncType.Color
        val height = if (isColor) {
            screenHeight / 3
        } else {
            screenHeight / 4
        }
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface)
        ) {
            val innerModifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            when (item.type) {
                FuncTitleModel.FuncType.Alpha -> {
                    SliderOption(
                        item = item,
                        modifier = innerModifier,
                        currentValue = waterMark.alpha.toFloat() / 255 * 100,
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.TextSize -> {
                    SliderOption(
                        item = item,
                        modifier = innerModifier,
                        currentValue = waterMark.textSize,
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.Vertical -> {
                    SliderOption(
                        item = item,
                        modifier = innerModifier,
                        currentValue = waterMark.vGap.toFloat(),
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.Horizon -> {
                    SliderOption(
                        item = item,
                        modifier = innerModifier,
                        currentValue = waterMark.hGap.toFloat(),
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.Degree -> {
                    SliderOption(
                        item = item,
                        modifier = innerModifier,
                        currentValue = waterMark.degree,
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.Color -> {
                    ColorOption(
                        item = item,
                        waterMark = waterMark,
                        modifier = innerModifier,
                        onChange = onChange
                    )
                }

                FuncTitleModel.FuncType.Icon -> {
                    IconOption(
                        item = item,
                        waterMark = waterMark,
                        modifier = innerModifier,
                        onIconSelected = onChange
                    )
                }

                FuncTitleModel.FuncType.Text -> {
                    TextContentOption(
                        item = item,
                        waterMark = waterMark,
                        modifier = innerModifier,
                        onTextChange = {}) {

                    }
                }

                FuncTitleModel.FuncType.TextTypeFace -> {
                    TextTypeface(
                        item = item,
                        waterMark = waterMark,
                        modifier = innerModifier,
                        onValueChange = onChange
                    )
                }

                FuncTitleModel.FuncType.TileMode ->
                    TileMode(
                        item = item,
                        waterMark = waterMark,
                        modifier = innerModifier,
                        onValueChange = onChange
                    )
            }
        }
    }
}


private val contentFunList: List<FuncTitleModel> by lazy {
    listOf(
        FuncTitleModel(
            FuncTitleModel.FuncType.Text,
            R.string.water_mark_mode_text,
            R.drawable.ic_func_text
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.Icon,
            R.string.water_mark_mode_image,
            R.drawable.ic_func_sticker
        )
    )
}

private val styleFunList: List<FuncTitleModel> by lazy {
    listOf(
        FuncTitleModel(
            FuncTitleModel.FuncType.TileMode,
            R.string.title_tile_mode,
            R.drawable.ic_tile_mode
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.TextSize,
            R.string.title_text_size,
            R.drawable.ic_func_size,
            valueRange = 1f..WaterMarkRepository.MAX_TEXT_SIZE,
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.TextTypeFace,
            R.string.title_text_style,
            R.drawable.ic_func_typeface
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.Color,
            R.string.title_text_color,
            R.drawable.ic_func_color
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.Alpha,
            R.string.style_alpha,
            R.drawable.ic_func_opacity,
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.Degree,
            R.string.title_text_rotate,
            R.drawable.ic_func_angle,
            valueRange = 0f..WaterMarkRepository.MAX_DEGREE,
        )
    )
}

private val layoutFunList: List<FuncTitleModel> by lazy {
    listOf(
        FuncTitleModel(
            FuncTitleModel.FuncType.Horizon,
            R.string.title_horizon_layout,
            R.drawable.ic_func_layour_horizontal,
            valueRange = 0f..WaterMarkRepository.MAX_VERTICAL_GAP.toFloat(),
        ),
        FuncTitleModel(
            FuncTitleModel.FuncType.Vertical,
            R.string.title_vertical_layout,
            R.drawable.ic_func_layout_vertical,
            valueRange = 0f..WaterMarkRepository.MAX_VERTICAL_GAP.toFloat(),
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { },
    onAddMoreImages: () -> Unit = { },
    onShowSaveDialog: () -> Unit = { },
    onGoAboutScreen: () -> Unit = { },
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0),
        title = {},
        navigationIcon = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onBack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "back"
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = {
                onAddMoreImages()
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_picker_image),
                    contentDescription = "add more images"
                )
            }
            IconButton(onClick = {
                onShowSaveDialog()
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_save),
                    contentDescription = "save"
                )
            }
            IconButton(onClick = {
                onGoAboutScreen()
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_about),
                    contentDescription = "about"
                )
            }
        }
    )
}

@Composable
fun WaterMarkView(
    modifier: Modifier = Modifier,
    waterMark: WaterMark,
    selectedImage: ImageInfo?,
    onUpdateUriFailed: (SecurityException) -> Unit = { },
    onScaleEnd: (textSize: Float) -> Unit = { },
    onOffsetChanged: (info: ImageInfo) -> Unit = { },
    onBgReady: (palette: Palette) -> Unit = { },
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        if (selectedImage == null) {
            Text(text = "No Image Selected", Modifier.align(Alignment.Center))
        } else {
            var componentHeight by remember { mutableStateOf(0.dp) }

            // get local density from composable
            val density = LocalDensity.current
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        componentHeight = with(density) { it.size.height.toDp() }
                    },
                factory = { context ->
                    WaterMarkImageView(context).apply {
                        scaleType = ImageView.ScaleType.MATRIX
                    }.apply {
                        onBgReady {
                            onBgReady(it)
                        }
                        onScaleEnd {
                            onScaleEnd(it)
                        }
                        onOffsetChanged {
                            onOffsetChanged(it)
                        }
                    }
                },
                update = {
                    if (componentHeight > 0.dp) {
                        with(it) {
                            config = waterMark
                            try {
                                updateUri(false, selectedImage)
                            } catch (se: SecurityException) {
                                se.printStackTrace()
                                onUpdateUriFailed(se)
                            }
                        }
                    }
                },
                onReset = {
                    with(it) {
                        config = waterMark
                        try {
                            updateUri(true, selectedImage)
                        } catch (se: SecurityException) {
                            se.printStackTrace()
                            onUpdateUriFailed(se)
                        }
                        reset()
                    }
                },
                onRelease = {
                    with(it) {
                        reset()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoList(
    imaList: List<ImageInfo>,
    selectedImage: ImageInfo?,
    modifier: Modifier = Modifier,
    onImageSelected: (ImageInfo) -> Unit = {},
    onImageDelete: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var optionWidth by remember {
        mutableStateOf(0.dp)
    }
    val itemWidth = 40.dp
    val density = LocalDensity.current
    LazyRow(
        modifier = modifier
            .onGloballyPositioned {
                optionWidth = with(density) {
                    it.size.width.toDp()
                }
            },
        contentPadding = PaddingValues(
            start = (optionWidth - itemWidth).coerceAtLeast(0.dp) / 2,
            end = (optionWidth - itemWidth).coerceAtLeast(0.dp) / 2
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        state = listState
    ) {
        items(imaList.size) {
            val imageInfo = imaList[it]
            PhotoItem(
                modifier = Modifier
                    .size(itemWidth)
                    .padding(4.dp)
                    .animateItem(),
                imageInfo = imageInfo,
                isSelected = imageInfo == selectedImage,
                onImageClick = { selectedImageInfo ->
                    coroutineScope.launch {
                        listState.animateScrollToItem(it)
                    }
                    onImageSelected.invoke(selectedImageInfo)
                },
                onImageDelete = onImageDelete
            )
        }
    }
}

@Composable
fun PhotoItem(
    imageInfo: ImageInfo,
    modifier: Modifier,
    isSelected: Boolean = false,
    onImageClick: (ImageInfo) -> Unit = {},
    onImageDelete: () -> Unit = {},
) {
    val border by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "")
    val padding by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "")
    val borderColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .border(
                width = border,
                color = borderColor
            )
            .padding(padding)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageInfo.uri)
                .crossfade(true)
                .build(),
            contentDescription = "image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    onImageClick(imageInfo)
                },
        )
    }
}

@Composable
fun BottomSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content
    )
}
