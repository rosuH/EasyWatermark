package me.rosuh.easywatermark.ui

import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.os.BuildCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController

import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.ui.compose.GalleryDialog
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
            CompositionLocalProvider(
                compositionLocalOf<WindowSizeClass> { error("SizeClass not present") } provides calculateWindowSizeClass(
                    this
                ),
            ) {
                AppTheme {
                    BottomSurface {
                        val navController = rememberNavController()
                        val state by viewModel.launchScreenUiStateFlow.collectAsStateWithLifecycle()
                        val context = this
                        val pickMultipleMedia =
                            rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                                // Callback is invoked after the user selects media items or closes the
                                // photo picker.
                                if (uris.isNotEmpty()) {
                                    Log.i(TAG, "PhotoPicker Number of items selected: ${uris.size}")
                                    viewModel.process(Action.SystemPickerImageSelected(uris))
                                    navController.navigate("EditorScreen")
                                } else {
                                    Log.i(TAG, "PhotoPicker No media selected")
                                }
                            }

                        NavHost(navController = navController, startDestination = "LaunchScreen") {
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