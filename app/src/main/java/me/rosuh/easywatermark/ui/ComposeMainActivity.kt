package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.platform.DynamicColorCapability
import org.koin.android.ext.android.inject
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.BuildCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import me.rosuh.easywatermark.R

import me.rosuh.easywatermark.data.model.FuncTitleModel
import android.widget.Toast
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutViewModel
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.ui.about.OpenSourceScreenStrings
import me.rosuh.easywatermark.utils.ktx.openLink
import me.rosuh.easywatermark.utils.ktx.toUri
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

    // S4d-43 (ADR-0007): live dynamic-color reads route through the platform capability (Android
    // delegates to the :cmonet module). Replaces the prior direct isDynamicColorAvailable() calls.
    private val dynamicColorCapability: DynamicColorCapability by inject()

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
        // Validate every shared uri, not just the first — a multi-share may mix in non-images.
        val images = uris.filter { FileUtils.isImage(contentResolver, it) }
        if (images.isNotEmpty()) {
            pendingShareUris = images
        }
    }

    // A stable launch resets the crash counter. Ported from legacy MainActivity.onResume,
    // which became dead once ComposeMainActivity took over as launcher (ADR-0016).
    override fun onResume() {
        super.onResume()
        if (MyApp.recoveryMode) return
        lifecycleScope.launch {
            delay(1000)
            if (!isFinishing) (application as? MyApp)?.launchSuccess()
        }
    }

    private fun crashStackTrace(): String =
        getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE).getString(MyApp.KEY_STACK_TRACE, "").orEmpty()

    private fun copyCrashInfo(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
            Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)

        // Crash-recovery self-heal: MyApp.recoveryMode is computed in MyApp.onCreate.
        // Port of the legacy MainActivity activity_recovery branch (ADR-0016).
        if (MyApp.recoveryMode) {
            setContent {
                AppTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        RecoveryScreen(
                            crashInfo = crashStackTrace(),
                            strings = RecoveryScreenStrings(
                                title = stringResource(R.string.recovery_title),
                                tips = stringResource(R.string.recovery_mode_tips),
                                copy = stringResource(R.string.copy),
                                sendEmail = "Send email",
                                sendTelegram = "Send Telegram",
                                jumpToStore = "Jump to Store",
                                turnOffRecovery = stringResource(R.string.turn_off_recovery_mode),
                            ),
                            onCopy = { copyCrashInfo(crashStackTrace()) },
                            onSendEmail = {
                                viewModel.extraCrashInfo(this@ComposeMainActivity, crashStackTrace())
                            },
                            onTelegram = { this@ComposeMainActivity.openLink("https://t.me/rosuh") },
                            onStore = {
                                this@ComposeMainActivity.openLink(
                                    Uri.parse("market://details?id=me.rosuh.easywatermark")
                                ) {
                                    Toast.makeText(
                                        this@ComposeMainActivity,
                                        R.string.store_not_found,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onCloseRecovery = {
                                (application as MyApp).launchSuccess()
                                Toast.makeText(
                                    this@ComposeMainActivity,
                                    R.string.recovery_mode_closed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
            return
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            CompositionLocalProvider(
                compositionLocalOf<WindowSizeClass> { error("SizeClass not present") } provides calculateWindowSizeClass(
                    this
                )
            ) {
                // Parity (ADR-0011): production applies Material You on dynamic-color-allowed devices.
                // S4d-43: routed through the ADR-0007 DynamicColorCapability (Android delegates to :cmonet).
                AppTheme(dynamicColor = dynamicColorCapability.isAvailable()) {
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
                                    // launchSingleTop + popUpTo: a share received while already in the
                                    // editor (onNewIntent) must not stack a second EditorScreen.
                                    navController.navigate(EditorRoute) {
                                        launchSingleTop = true
                                        popUpTo(LaunchRoute)
                                    }
                                    pendingShareUris = null
                                }
                            }
                            var showSaveSheet by remember { mutableStateOf(false) }
                            val userPreferences by viewModel.userPreferences.collectAsStateWithLifecycle()
                            val state by viewModel.launchScreenUiStateFlow.collectAsStateWithLifecycle()
                            val saveExportState by viewModel.saveExportUiState.collectAsStateWithLifecycle()
                            val context = LocalContext.current
                            val templates by viewModel.templateListFlow.collectAsStateWithLifecycle()

                            val doExport: () -> Unit = {
                                viewModel.saveImage(
                                    context.contentResolver,
                                    state.selectedImageList
                                )
                            }

                            val outputUris = state.selectedImageList.mapNotNull { image ->
                                image.result?.data as? Uri
                            }
                            val shareExports: () -> Unit = {
                                if (outputUris.isNotEmpty()) {
                                    val intent = Intent().apply {
                                        type = "image/*"
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        if (outputUris.size == 1) {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_STREAM, outputUris.single())
                                        } else {
                                            action = Intent.ACTION_SEND_MULTIPLE
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outputUris))
                                        }
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: SecurityException) {
                                        Toast.makeText(context, "Share error with ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            val openFirstExport: () -> Unit = {
                                outputUris.firstOrNull()?.let { outputUri ->
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(outputUri, "image/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
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
                                        navController.navigate(EditorRoute)
                                    } else {
                                        Log.i(TAG, "PhotoPicker No media selected")
                                    }
                                }

                            NavHost(
                                navController = navController,
                                startDestination = LaunchRoute,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .imePadding()
                            ) {
                                composable<LaunchRoute> {
                                    LaunchScreen(
                                        onGoDialog = { navController.navigate(GalleryDialogRoute) },
                                        onGoAbout = { navController.navigate(AboutRoute) }
                                    )
                                }
                                dialog<GalleryDialogRoute>(
                                    dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
                                ) {
                                    GalleryDialog(
                                        state.imageList,
                                        onLoadImages = {
                                            viewModel.process(Action.LoadImages(context.contentResolver))
                                        },
                                        onDismiss = { selected ->
                                            navController.popBackStack()
                                            if (selected) {
                                                navController.navigate(EditorRoute)
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
                                composable<EditorRoute> {
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
                                            navController.navigate(AboutRoute)
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
                                        templates = templates,
                                        onUseTemplate = { template ->
                                            template.content?.let { viewModel.updateText(it) }
                                        },
                                        onAddTemplate = { content ->
                                            viewModel.addTemplate(content)
                                        },
                                        onUpdateTemplate = { template ->
                                            viewModel.updateTemplate(template)
                                        },
                                        onDeleteTemplate = { template ->
                                            viewModel.deleteTemplate(template)
                                        }
                                    )
                                }
                                composable<AboutRoute> {
                                    val wm by aboutViewModel.waterMark.collectAsStateWithLifecycle()
                                    AboutScreen(
                                        versionName = BuildConfig.VERSION_NAME,
                                        showBounds = wm?.enableBounds ?: false,
                                        dynamicColorOn = dynamicColorCapability.isAvailable(),
                                        onBack = { navController.popBackStack() },
                                        onOpenLink = { url -> this@ComposeMainActivity.openLink(url) },
                                        onOpenSource = {
                                            navController.navigate(OpenSourceRoute)
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
                                composable<OpenSourceRoute> {
                                    OpenSourceScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenLink = { url -> this@ComposeMainActivity.openLink(url) },
                                        backIcon = painterResource(R.drawable.ic_back),
                                        strings = OpenSourceScreenStrings(
                                            title = stringResource(R.string.about_title_open_source),
                                            aboutLibDesc = stringResource(R.string.open_source_desc_about_lib),
                                            materialComponentsDesc = stringResource(R.string.open_source_desc_material_components),
                                            compressorDesc = stringResource(R.string.open_source_desc_compressor),
                                        ),
                                    )
                                }
                            }

                            if (showSaveSheet) {
                                val exportTotalCount = saveExportState.totalCount
                                    .takeIf { it > 0 }
                                    ?: state.selectedImageList.size
                                SaveExportSheet(
                                    imageCount = state.selectedImageList.size,
                                    imageUris = state.selectedImageList.map { it.uri.toUri() },
                                    selectedFormatLabel = userPreferences.outputFormat,
                                    quality = userPreferences.compressLevel,
                                    resultSummaryText = "${saveExportState.completedCount}/${exportTotalCount}",
                                    primaryActionLabel = when {
                                        saveExportState.isSaving -> stringResource(R.string.dialog_save_exporting)
                                        saveExportState.isFinished -> stringResource(R.string.share)
                                        else -> stringResource(R.string.dialog_export_to_gallery)
                                    },
                                    primaryActionEnabled = !saveExportState.isSaving,
                                    showOpenGallery = saveExportState.isFinished && outputUris.isNotEmpty(),
                                    onDismiss = { showSaveSheet = false },
                                    onFormatClick = { newFormat ->
                                        viewModel.saveOutput(newFormat)
                                    },
                                    onQualityChange = { q ->
                                        viewModel.saveOutput(level = q)
                                    },
                                    onExportClick = {
                                        if (saveExportState.isFinished) {
                                            shareExports()
                                        } else {
                                            if (state.selectedImageList.isEmpty()) {
                                                return@SaveExportSheet
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                doExport()
                                            } else {
                                                permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            }
                                        }
                                    },
                                    onOpenGalleryClick = openFirstExport,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
