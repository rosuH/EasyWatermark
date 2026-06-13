package me.rosuh.easywatermark.ui

import me.rosuh.cmonet.CMonet
import android.os.Build
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
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import me.rosuh.easywatermark.utils.FileUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.BuildCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import me.rosuh.easywatermark.R

import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ViewInfo
import android.widget.Toast
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutViewModel
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.utils.ktx.openLink
import me.rosuh.easywatermark.ui.compose.GalleryDialog
import me.rosuh.easywatermark.ui.save.SaveExportSheet
import org.koin.androidx.viewmodel.ext.android.viewModel

@BuildCompat.PrereleaseSdkCheck

class ComposeMainActivity : ComponentActivity() {

    companion object {
        const val TAG = "ComposeMainActivity"
    }

    private val viewModel: MainViewModel by viewModel()

    private val aboutViewModel: AboutViewModel by viewModel()

    // ACTION_SEND share-in bridge (ADR-0016): set from intent, observed in setContent → navigate.
    private var pendingShareUris by mutableStateOf<List<Uri>?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                listOfNotNull(stream ?: intent.data)
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty() && FileUtils.isImage(contentResolver, uris.first())) {
            pendingShareUris = uris
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            CompositionLocalProvider(
                compositionLocalOf<WindowSizeClass> { error("SizeClass not present") } provides calculateWindowSizeClass(
                    this
                )
            ) {
                // Parity (ADR-0011): production applies Material You on CMonet-allowed devices.
                // Bridge via cmonet until ADR-0007's platform capability replaces it.
                AppTheme(dynamicColor = CMonet.isDynamicColorAvailable()) {
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
                            LaunchedEffect(pendingShareUris) {
                                pendingShareUris?.let { uris ->
                                    viewModel.updateImageList(uris)
                                    navController.navigate("EditorScreen")
                                    pendingShareUris = null
                                }
                            }
                            var showSaveSheet by remember { mutableStateOf(false) }
                            val userPreferences by viewModel.userPreferences.collectAsStateWithLifecycle()
                            val state by viewModel.launchScreenUiStateFlow.collectAsStateWithLifecycle()
                            val context = LocalContext.current
                            val viewInfo by viewModel.viewInfoStateFlow.collectAsStateWithLifecycle()

                            // [DEBUG] temporary export instrumentation — remove after diagnosing
                            val saveResult by viewModel.saveResult.observeAsState()
                            LaunchedEffect(saveResult) {
                                Log.d("EXPORT", "saveResult code=${saveResult?.code} data=${saveResult?.data}")
                            }

                            val doExport: () -> Unit  = {
                                Log.d("EXPORT", "doExport called imageList=${state.selectedImageList.size} viewInfo=$viewInfo")
                                viewModel.saveImage(
                                    context.contentResolver,
                                    viewInfo,
                                    state.selectedImageList
                                )
                            }

                            val currentDoExport by rememberUpdatedState(doExport)
                            val permissionLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestPermission()
                            ) { permissions ->
                                if (permissions) {
                                    currentDoExport()
                                }
                            }
                            val pickMultipleMedia =
                                rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                                    if (uris.isNotEmpty()) {
                                        Log.i(
                                            TAG,
                                            "PhotoPicker Number of items selected: ${uris.size}"
                                        )
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
                                    LaunchScreen(
                                        onGoDialog = { navController.navigate("GalleryDialog") },
                                        onGoAbout = { navController.navigate("AboutScreen") }
                                    )
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
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
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
                                        },
                                        onGoAboutScreen = {
                                            navController.navigate("AboutScreen")
                                        },
                                        onAddMoreImages = {
                                            pickMultipleMedia.launch(
                                                PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                        onShowSaveDialog = {
                                            showSaveSheet = true
                                        },
                                        onViewInfoChanged = { vi ->
                                            viewModel.updateViewInfo(vi)
                                        }
                                    )
                                }
                                composable("AboutScreen") {
                                    val wm by aboutViewModel.waterMark.observeAsState()
                                    AboutScreen(
                                        versionName = BuildConfig.VERSION_NAME,
                                        showBounds = wm?.enableBounds ?: false,
                                        dynamicColorOn = CMonet.isDynamicColorAvailable(),
                                        onBack = { navController.popBackStack() },
                                        onOpenLink = { url -> this@ComposeMainActivity.openLink(url) },
                                        onOpenSource = {
                                            navController.navigate("OpenSourceScreen")
                                        },
                                        onToggleBounds = { aboutViewModel.toggleBounds(it) },
                                        onToggleDynamicColor = {
                                            aboutViewModel.toggleSupportDynamicColor(it)
                                            Toast.makeText(
                                                this@ComposeMainActivity,
                                                "Reboot and you'll get what you want.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                                composable("OpenSourceScreen") {
                                    OpenSourceScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenLink = { url -> this@ComposeMainActivity.openLink(url) }
                                    )
                                }
                            }

                            if (showSaveSheet) {
                                SaveExportSheet(
                                    imageCount = state.selectedImageList.size,
                                    imageUris = state.selectedImageList.map { it.uri },
                                    selectedFormatLabel = userPreferences.outputFormat,
                                    quality = userPreferences.compressLevel,
                                    isSaving = false,
                                    finishedCount = 0,
                                    totalCount = state.selectedImageList.size,
                                    resultSummaryText = "0/${state.selectedImageList.size}",
                                    primaryActionLabel = stringResource(R.string.dialog_export_to_gallery),
                                    onDismiss = { showSaveSheet = false },
                                    onFormatClick = { newFormat ->
                                        viewModel.saveOutput(newFormat)
                                    },
                                    onQualityChange = { q ->
                                        viewModel.saveOutput(level = q)
                                    },
                                    onExportClick = {
                                        Log.d("EXPORT", "click viewInfo=$viewInfo isEmpty=${viewInfo == ViewInfo.Empty} sdk=${Build.VERSION.SDK_INT}")
                                        if (viewInfo == ViewInfo.Empty) {
                                            return@SaveExportSheet
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            doExport()
                                        } else {
                                            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                    },
                                    onShareClick = {},
                                    onOpenGalleryClick = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
