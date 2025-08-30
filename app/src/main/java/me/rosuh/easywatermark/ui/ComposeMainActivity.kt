package me.rosuh.easywatermark.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.BuildCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController

import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.ui.compose.GalleryDialog
import me.rosuh.easywatermark.utils.ktx.dp
import org.koin.androidx.viewmodel.ext.android.viewModel

@BuildCompat.PrereleaseSdkCheck

class ComposeMainActivity : ComponentActivity() {

    companion object {
        const val TAG = "ComposeMainActivity"
    }

    private val viewModel: MainViewModel by viewModel()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            CompositionLocalProvider(
                compositionLocalOf<WindowSizeClass> { error("SizeClass not present") } provides calculateWindowSizeClass(this)
            ) {
                AppTheme {
                    val surfaceColor = MaterialTheme.colorScheme.surface
                    val isDark = surfaceColor.luminance() < 0.5f

                    // 设置系统UI为透明系统栏
                    SideEffect {
                        val transparent = Color.Transparent.toArgb()
                        enableEdgeToEdge(
                            statusBarStyle = if (isDark) {
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.light(transparent, Color.Black.toArgb())
                            },
                            navigationBarStyle = if (isDark) {
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.light(transparent, Color.Black.toArgb())
                            }
                        )
                    }

                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            val navController = rememberNavController()
                            val state by viewModel.launchScreenUiStateFlow.collectAsStateWithLifecycle()
                            val context = this@ComposeMainActivity
                            val pickMultipleMedia =
                                rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                                    if (uris.isNotEmpty()) {
                                        Log.i(TAG, "PhotoPicker Number of items selected: ${uris.size}")
                                        viewModel.process(Action.SystemPickerImageSelected(uris))
                                        navController.navigate("EditorScreen")
                                    } else {
                                        Log.i(TAG, "PhotoPicker No media selected")
                                    }
                                }

                            NavHost(
                                navController = navController,
                                startDestination = "LaunchScreen",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .imePadding()
                            ) {
                                composable("LaunchScreen") {
                                    LaunchScreen {
                                        navController.navigate("GalleryDialog")
                                    }
                                }
                                dialog(
                                    "GalleryDialog",
                                    dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
                                ) {
                                    GalleryDialog(
                                        state.imageList,
                                        onLoaImages = {
                                            viewModel.process(Action.LoadImages(context.contentResolver))
                                        },
                                        onDismiss = { selected ->
                                            navController.popBackStack()
                                            if (selected) {
                                                navController.navigate("EditorScreen")
                                            }
                                            viewModel.process(Action.DialogDismiss(selected))
                                        },
                                        onImageSelected = { image, index, isSelected ->
                                            viewModel.process(
                                                Action.GalleryImageSelected(
                                                    image,
                                                    index,
                                                    isSelected
                                                )
                                            )
                                        },
                                        onPickImageViaSystem = {
                                            pickMultipleMedia.launch(
                                                PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    )
                                }
                                composable("EditorScreen") {
                                    EditorScreen(
                                        imageList = state.selectedImageList,
                                        waterMark = state.waterMark,
                                        selectedImage = state.curImageInfo,
                                        onBack = { navController.popBackStack() },
                                        onWaterMrkChange = { item: FuncTitleModel, any: Any ->
                                            viewModel.process(Action.WaterMarkChange(item, any))
                                        },
                                        onImageSelected = {
                                            viewModel.process(Action.EditorImageSelected(it))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
