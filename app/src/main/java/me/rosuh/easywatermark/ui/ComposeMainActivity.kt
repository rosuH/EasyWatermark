package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.platform.DynamicColorCapability
import org.koin.android.ext.android.inject
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.copy_failed
import me.rosuh.easywatermark.shared.generated.resources.copy_success
import me.rosuh.easywatermark.shared.generated.resources.dev_comment
import me.rosuh.easywatermark.shared.generated.resources.dialog_cancel_exist_confirm
import me.rosuh.easywatermark.shared.generated.resources.dialog_content_exist_confirm
import me.rosuh.easywatermark.shared.generated.resources.dialog_export_to_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_done
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_failed
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_partial
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_success
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_exporting
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_exist_confirm
import me.rosuh.easywatermark.shared.generated.resources.recovery_mode_closed
import me.rosuh.easywatermark.shared.generated.resources.share
import me.rosuh.easywatermark.shared.generated.resources.store_not_found
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import org.jetbrains.compose.resources.stringResource as cmpStringResource
import androidx.core.os.BuildCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import android.widget.Toast
import androidx.compose.runtime.Composable
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutViewModel
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.utils.ktx.openLink
import me.rosuh.easywatermark.utils.ktx.toUri
import me.rosuh.easywatermark.utils.ktx.uriFromExportResultData
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.ui.compose.GalleryDialog
import me.rosuh.easywatermark.ui.save.SaveExportSheetShell
import org.koin.androidx.viewmodel.ext.android.viewModel

@BuildCompat.PrereleaseSdkCheck

class ComposeMainActivity : ComponentActivity() {

    companion object {
        const val TAG = "ComposeMainActivity"
    }

    private val viewModel: MainViewModel by viewModel()

    private val aboutViewModel: AboutViewModel by viewModel()

    // (ADR-0007): live dynamic-color reads route through the platform capability (Android
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
    //
    // Delay is intentionally long: the previous 1s window wiped the crash streak as soon as
    // the launch screen appeared, so mid-session crashes (e.g. pick → editor) never reached
    // recoveryMode (needs CRASH_COUNT=2 without a successful stable window in between).
    override fun onResume() {
        super.onResume()
        if (MyApp.recoveryMode) return
        lifecycleScope.launch {
            delay(30_000)
            if (!isFinishing) (application as? MyApp)?.launchSuccess()
        }
    }

    private fun crashStackTrace(): String =
        getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE).getString(MyApp.KEY_STACK_TRACE, "").orEmpty()

    private fun copyCrashInfo(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
            Toast.makeText(this, sharedString(Res.string.copy_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, sharedString(Res.string.copy_failed), Toast.LENGTH_SHORT).show()
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
                                        sharedString(Res.string.store_not_found),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onCloseRecovery = {
                                (application as MyApp).launchSuccess()
                                Toast.makeText(
                                    this@ComposeMainActivity,
                                    sharedString(Res.string.recovery_mode_closed),
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
                // routed through the ADR-0007 DynamicColorCapability (Android delegates to :cmonet).
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

                    // Shared Launch/Editor/About apply [Modifier.safeDrawingPadding] themselves
                    // (CMP-safe immersive). Scaffold must not double-apply safeDrawing insets.
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { _ ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            // E0: Session owns product route (Launch/Editor/About). Activity only
                            // owns presentation/system edges (picker, gallery dialog, export, links).
                            var showGalleryDialog by remember { mutableStateOf(false) }
                            var showOpenSource by remember { mutableStateOf(false) }
                            var showSaveSheet by remember { mutableStateOf(false) }

                            // E2: stage share grants to app-owned files before Session selection.
                            LaunchedEffect(pendingShareUris) {
                                pendingShareUris?.let { uris ->
                                    viewModel.stageShareAndEnterEditor(uris)
                                    pendingShareUris = null
                                }
                            }
                            // E2: cold start / process-death restore of minimal durable source ids.
                            LaunchedEffect(Unit) {
                                if (pendingShareUris == null) {
                                    viewModel.restoreEditorIfDurable()
                                }
                            }
                            val userPreferences by viewModel.userPreferences.collectAsStateWithLifecycle()
                            val state by viewModel.launchScreenUiStateFlow.collectAsStateWithLifecycle()
                            val saveExportState by viewModel.saveExportUiState.collectAsStateWithLifecycle()
                            // Clear restore ids only after an Editor session returns to Launch (not cold Launch).
                            var sawEditorSession by remember { mutableStateOf(false) }
                            LaunchedEffect(state.uiState) {
                                when (state.uiState) {
                                    LaunchScreenUiState.Editor -> sawEditorSession = true
                                    LaunchScreenUiState.Launch -> {
                                        if (sawEditorSession) {
                                            viewModel.clearSessionRestore()
                                            sawEditorSession = false
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            val productRoute = ProductShellNav.routeFromLaunchUi(state.uiState)
                            val context = LocalContext.current
                            val templates by viewModel.templateListFlow.collectAsStateWithLifecycle()

                            val doExport: () -> Unit = {
                                viewModel.saveImage(
                                    context.contentResolver,
                                    state.selectedImageList
                                )
                            }

                            // Export port returns MediaRef; convert at the Android Intent edge.
                            val outputUris = state.selectedImageList.mapNotNull { image ->
                                uriFromExportResultData(image.result?.data)
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
                                        // System picker → Session EnterEditor (productRoute from Session).
                                        viewModel.process(Action.SystemPickerImageSelected(uris))
                                        showGalleryDialog = false
                                    } else {
                                        Log.i(TAG, "PhotoPicker No media selected")
                                    }
                                }

                            // Gallery-mode path only: open dialog when full or partial media access.
                            val galleryPermissionLauncher =
                                rememberLauncherForActivityResult(
                                    ActivityResultContracts.RequestMultiplePermissions(),
                                ) { results ->
                                    if (hasReadableMediaAccess(this@ComposeMainActivity, results)) {
                                        showGalleryDialog = true
                                    } else {
                                        Log.i(TAG, "Media permission denied (full and partial)")
                                    }
                                }

                            val openSystemPhotoPicker: () -> Unit = {
                                pickMultipleMedia.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }
                            val openInAppGallery: () -> Unit = {
                                if (hasReadableMediaAccess(this@ComposeMainActivity)) {
                                    showGalleryDialog = true
                                } else {
                                    galleryPermissionLauncher.launch(mediaPermissionRequestKeys())
                                }
                            }
                            val onPickImages: () -> Unit = {
                                if (userPreferences.preferInAppGallery) {
                                    openInAppGallery()
                                } else {
                                    openSystemPhotoPicker()
                                }
                            }

                            // v2.10.0 parity: leaving the editor asks to discard changes first
                            // (non-cancelable; Confirm = reset session + back to Launch).
                            var showEditorExitConfirm by remember { mutableStateOf(false) }

                            // System back: OpenSource → About → prior route; Editor → discard confirm.
                            BackHandler(enabled = showOpenSource) { showOpenSource = false }
                            BackHandler(enabled = !showOpenSource && productRoute == ProductShellNav.Route.About) {
                                viewModel.onBackPressed()
                            }
                            BackHandler(enabled = !showOpenSource && productRoute == ProductShellNav.Route.Editor) {
                                showEditorExitConfirm = true
                            }
                            BackHandler(enabled = showGalleryDialog) {
                                showGalleryDialog = false
                                viewModel.process(Action.DialogDismiss(false))
                            }

                            if (showEditorExitConfirm) {
                                AlertDialog(
                                    onDismissRequest = { },
                                    properties = DialogProperties(
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = false,
                                    ),
                                    title = {
                                        Text(cmpStringResource(Res.string.dialog_title_exist_confirm))
                                    },
                                    text = {
                                        Text(cmpStringResource(Res.string.dialog_content_exist_confirm))
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showEditorExitConfirm = false
                                                viewModel.resetJobStatus()
                                                viewModel.clearData()
                                                viewModel.onBackPressed()
                                            }
                                        ) {
                                            Text(cmpStringResource(Res.string.tips_confirm_dialog))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showEditorExitConfirm = false }) {
                                            Text(cmpStringResource(Res.string.dialog_cancel_exist_confirm))
                                        }
                                    },
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .imePadding(),
                            ) {
                                ProductShellHost(
                                    route = productRoute,
                                ) { route ->
                                    when (route) {
                                        ProductShellNav.Route.Launch -> {
                                            AndroidLaunchScreen(
                                                onPickImage = onPickImages,
                                                onGoAbout = {
                                                    viewModel.openAbout(
                                                        me.rosuh.easywatermark.ui.LaunchScreenUiState.Launch,
                                                    )
                                                },
                                            )
                                        }
                                        ProductShellNav.Route.Editor -> {
                                            AndroidEditorScreen(
                                                imageList = state.selectedImageList,
                                                waterMark = state.waterMark,
                                                selectedImage = state.curImageInfo,
                                                onBack = {
                                                    showEditorExitConfirm = true
                                                },
                                                onOffsetChanged = { info ->
                                                    viewModel.updateOffset(info)
                                                },
                                                onWaterMrkChange = { change ->
                                                    viewModel.applyConfig(change)
                                                },
                                                onIconPicked = viewModel::importWatermarkIcon,
                                                onImageSelected = {
                                                    viewModel.process(Action.EditorImageSelected(it))
                                                },
                                                onGoAboutScreen = {
                                                    viewModel.openAbout(
                                                        me.rosuh.easywatermark.ui.LaunchScreenUiState.Editor,
                                                    )
                                                },
                                                onAddMoreImages = onPickImages,
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
                                                },
                                            )
                                        }
                                        ProductShellNav.Route.About -> {
                                            val wm by aboutViewModel.waterMark.collectAsStateWithLifecycle()
                                            // Bind to the force-toggle SP flag, NOT isAvailable():
                                            // on Samsung/Google, isAvailable() stays true even when
                                            // force is off, so a controlled Switch never visually flips.
                                            var forceDynamicColor by remember {
                                                mutableStateOf(dynamicColorCapability.isForcedSupport())
                                            }
                                            AboutScreenAndroid(
                                                versionName = BuildConfig.VERSION_NAME,
                                                showBounds = wm?.enableBounds ?: false,
                                                dynamicColorOn = forceDynamicColor,
                                                preferInAppGallery = userPreferences.preferInAppGallery,
                                                onBack = { viewModel.onBackPressed() },
                                                onOpenLink = { url ->
                                                    this@ComposeMainActivity.openLink(url)
                                                },
                                                onOpenSource = { showOpenSource = true },
                                                onToggleBounds = { aboutViewModel.toggleBounds(it) },
                                                onToggleDynamicColor = { enabled ->
                                                    aboutViewModel.toggleSupportDynamicColor(enabled)
                                                    forceDynamicColor = enabled
                                                    Toast.makeText(
                                                        this@ComposeMainActivity,
                                                        "Reboot and you'll get what you want.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                onTogglePreferInAppGallery = { enabled ->
                                                    viewModel.setPreferInAppGallery(enabled)
                                                },
                                            )
                                        }
                                    }
                                }

                                if (showOpenSource) {
                                    OpenSourceScreen(
                                        onBack = { showOpenSource = false },
                                        onOpenLink = { url ->
                                            this@ComposeMainActivity.openLink(url)
                                        },
                                        backIcon = SharedProductDrawables.backPainter(),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            if (showGalleryDialog) {
                                Dialog(
                                    onDismissRequest = {
                                        showGalleryDialog = false
                                        viewModel.process(Action.DialogDismiss(false))
                                    },
                                    properties = DialogProperties(usePlatformDefaultWidth = false),
                                ) {
                                    // Selection is local in the dialog (no per-tap list rebuild).
                                    // Commit once on FAB dismiss via selectGallery.
                                    GalleryDialog(
                                        images = state.imageList,
                                        onLoadImages = {
                                            viewModel.process(
                                                Action.LoadImages(context.contentResolver)
                                            )
                                        },
                                        onDismiss = { selectedImages ->
                                            showGalleryDialog = false
                                            if (selectedImages.isNotEmpty()) {
                                                // selectGallery → EnterEditor (Session uiState=Editor).
                                                viewModel.selectGallery(selectedImages)
                                            } else {
                                                viewModel.process(Action.DialogDismiss(false))
                                            }
                                        },
                                        onPickImageViaSystem = openSystemPhotoPicker,
                                    )
                                }
                            }

                            if (showSaveSheet) {
                                val exportImages = state.selectedImageList
                                val exportTotalCount = exportImages.size.coerceAtLeast(1)
                                // D5: Session counts are source of truth (not host invent).
                                val successCount = saveExportState.successCount
                                    .coerceAtLeast(saveExportState.completedCount)
                                val failureCount = saveExportState.failureCount
                                val processedCount = saveExportState.processedCount
                                    .coerceAtLeast(successCount + failureCount)
                                val totalCount = saveExportState.totalCount
                                    .takeIf { it > 0 } ?: exportImages.size
                                val recovery = me.rosuh.easywatermark.ui.save.ExportRecoveryUi.fromJob(
                                    isSaving = saveExportState.isSaving,
                                    isFinished = saveExportState.isFinished,
                                    successCount = successCount,
                                    failureCount = failureCount,
                                    processedCount = processedCount,
                                    totalCount = totalCount.coerceAtLeast(exportTotalCount),
                                )
                                val resultSummaryText = when {
                                    recovery.isExporting -> cmpStringResource(
                                        Res.string.dialog_save_export_progress,
                                        recovery.processedCount,
                                        recovery.totalCount.coerceAtLeast(1),
                                    )
                                    recovery.isFinished && recovery.failureCount == 0 && recovery.successCount > 0 ->
                                        cmpStringResource(
                                            Res.string.dialog_save_export_done_success,
                                            recovery.successCount,
                                            recovery.totalCount.coerceAtLeast(1),
                                        )
                                    recovery.isFinished && recovery.successCount > 0 && recovery.failureCount > 0 ->
                                        cmpStringResource(
                                            Res.string.dialog_save_export_done_partial,
                                            recovery.successCount,
                                            recovery.totalCount.coerceAtLeast(1),
                                            recovery.failureCount,
                                        )
                                    recovery.isFinished && recovery.successCount == 0 ->
                                        cmpStringResource(
                                            Res.string.dialog_save_export_done_failed,
                                            recovery.totalCount.coerceAtLeast(1),
                                        )
                                    else -> "${recovery.successCount}/${recovery.totalCount.coerceAtLeast(1)}"
                                }
                                val statusCd = if (recovery.isExporting) {
                                    cmpStringResource(
                                        Res.string.dialog_save_export_cd_progress,
                                        recovery.processedCount,
                                        recovery.totalCount.coerceAtLeast(1),
                                        recovery.successCount,
                                        recovery.failureCount,
                                    )
                                } else {
                                    cmpStringResource(
                                        Res.string.dialog_save_export_cd_done,
                                        recovery.successCount,
                                        recovery.failureCount,
                                        recovery.totalCount.coerceAtLeast(1),
                                    )
                                }
                                // Pack ticks into one int so thumbnails recompose on each export step.
                                val exportTick =
                                    saveExportState.processedCount * 10 +
                                        successCount * 100 +
                                        failureCount * 1000 +
                                        (if (saveExportState.isSaving) 1 else 0) +
                                        (if (saveExportState.isFinished) 2 else 0)
                                SaveExportSheetAndroid(
                                    imageCount = exportImages.size,
                                    images = exportImages,
                                    selectedFormatLabel = userPreferences.outputFormat,
                                    quality = userPreferences.compressLevel,
                                    resultSummaryText = resultSummaryText,
                                    statusContentDescription = statusCd,
                                    primaryActionLabel = when {
                                        saveExportState.isSaving -> cmpStringResource(Res.string.dialog_save_exporting)
                                        saveExportState.isFinished -> cmpStringResource(Res.string.share)
                                        else -> cmpStringResource(Res.string.dialog_export_to_gallery)
                                    },
                                    primaryActionEnabled = !saveExportState.isSaving,
                                    showOpenGallery = saveExportState.isFinished && outputUris.isNotEmpty(),
                                    isExporting = recovery.isExporting,
                                    showCancelButton = recovery.showCancel,
                                    showRetryFailedButton = recovery.showRetryFailed,
                                    exportTick = exportTick,
                                    onDismiss = {
                                        if (!saveExportState.isSaving) showSaveSheet = false
                                    },
                                    onFormatClick = { newFormat ->
                                        viewModel.saveOutput(newFormat)
                                    },
                                    onQualityChange = { q ->
                                        viewModel.saveOutput(level = q)
                                    },
                                    onCancelClick = { viewModel.cancelExport() },
                                    onRetryFailedClick = {
                                        if (exportImages.isEmpty()) return@SaveExportSheetAndroid
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            doExport()
                                        } else {
                                            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                    },
                                    onExportClick = {
                                        if (saveExportState.isFinished) {
                                            shareExports()
                                        } else {
                                            if (exportImages.isEmpty()) {
                                                return@SaveExportSheetAndroid
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

/**
 * Android edge for the shared About shell: resources, URL routing, and legacy animated logo.
 * Kept private in this file so there is no standalone app About wrapper type.
 */
@Composable
private fun AboutScreenAndroid(
    versionName: String,
    showBounds: Boolean,
    dynamicColorOn: Boolean,
    preferInAppGallery: Boolean,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenSource: () -> Unit,
    onToggleBounds: (Boolean) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onTogglePreferInAppGallery: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AboutScreen(
        versionName = versionName,
        showBounds = showBounds,
        dynamicColorOn = dynamicColorOn,
        icons = AboutScreenIcons(
            back = SharedProductDrawables.backPainter(),
            version = SharedProductDrawables.versionPainter(),
            rating = SharedProductDrawables.ratePainter(),
            feedback = SharedProductDrawables.feedbackPainter(),
            updateLog = SharedProductDrawables.updateLogPainter(),
            openSource = SharedProductDrawables.openSourcePainter(),
            privacyZh = SharedProductDrawables.privacyZhPainter(),
            privacyEn = SharedProductDrawables.privacyEnPainter(),
        ),
        developerCard = AboutDevCard(
            title = "Developed with ♥ by rosu",
            description = cmpStringResource(Res.string.dev_comment),
            avatar = SharedProductDrawables.avatarDevPainter(),
        ),
        designerCard = AboutDevCard(
            title = "Designed with ♥ by tovi",
            description = "A Designer.",
            avatar = SharedProductDrawables.avatarToviPainter(),
        ),
        onBack = onBack,
        onVersion = { onOpenLink(ABOUT_URL_RELEASES) },
        onRate = { onOpenLink(ABOUT_URL_MARKET) },
        onFeedback = { onOpenLink(ABOUT_URL_ISSUES) },
        onUpdateLog = { onOpenLink(ABOUT_URL_RELEASES) },
        onOpenSource = onOpenSource,
        onPrivacyZh = { onOpenLink(ABOUT_URL_PRIVACY_ZH) },
        onPrivacyEn = { onOpenLink(ABOUT_URL_PRIVACY_EN) },
        onDeveloper = { onOpenLink(ABOUT_URL_DEV) },
        onDesigner = { onOpenLink(ABOUT_URL_DESIGNER) },
        onToggleBounds = onToggleBounds,
        onToggleDynamicColor = onToggleDynamicColor,
        showPreferInAppGallerySwitch = true,
        preferInAppGallery = preferInAppGallery,
        onTogglePreferInAppGallery = onTogglePreferInAppGallery,
        modifier = modifier,
        logo = { logoModifier ->
            me.rosuh.easywatermark.ui.AboutPageLogo(
                modifier = logoModifier,
                animate = true,
            )
        },
    )
}

/** Permissions to request when opening the in-app gallery (not Photo Picker). */
private fun mediaPermissionRequestKeys(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * True when the app can list MediaStore images: full library grant, Android 14+ partial
 * (user-selected), or legacy external storage.
 */
private fun hasReadableMediaAccess(
    context: Context,
    grantResults: Map<String, Boolean>? = null,
): Boolean {
    fun granted(permission: String): Boolean {
        grantResults?.get(permission)?.let { return it }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> true
        else -> false
    }
}

// About URL edges (byte-identical to former AboutScreen.kt constants).
private const val ABOUT_URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val ABOUT_URL_MARKET = "market://details?id=me.rosuh.easywatermark"
private const val ABOUT_URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val ABOUT_URL_PRIVACY_ZH = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val ABOUT_URL_PRIVACY_EN = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val ABOUT_URL_DEV = "https://github.com/rosuH"
private const val ABOUT_URL_DESIGNER = "https://tovi.fun/"

/**
 * Android edge for the shared save/export sheet: Coil URI thumbs + per-item jobState
 * Progress overlay. Export/share/MediaStore/permission stay on the Activity call site. */
@Composable
private fun SaveExportSheetAndroid(
    imageCount: Int,
    images: List<me.rosuh.easywatermark.data.model.ImageInfo> = emptyList(),
    selectedFormatLabel: ImageFormat,
    quality: Int,
    resultSummaryText: String,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean = true,
    showOpenGallery: Boolean = true,
    isExporting: Boolean = false,
    showCancelButton: Boolean = false,
    showRetryFailedButton: Boolean = false,
    statusContentDescription: String = resultSummaryText,
    /** Recomposition tick while exporting (processedCount / isSaving / isFinished). */
    exportTick: Int = 0,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onCancelClick: (() -> Unit)? = null,
    onRetryFailedClick: (() -> Unit)? = null,
    onOpenGalleryClick: () -> Unit,
) {
    SaveExportSheetShell(
        items = images,
        selectedFormat = selectedFormatLabel,
        quality = quality,
        exportListSubtitle = resultSummaryText,
        imageCount = imageCount,
        primaryActionLabel = primaryActionLabel,
        primaryActionEnabled = primaryActionEnabled,
        showOpenGallery = showOpenGallery,
        isExporting = isExporting,
        showCancelButton = showCancelButton,
        onCancelClick = onCancelClick,
        showRetryFailedButton = showRetryFailedButton,
        onRetryFailedClick = onRetryFailedClick,
        statusContentDescription = statusContentDescription,
        itemKey = { it.uri.value },
        onDismiss = onDismiss,
        onFormatClick = onFormatClick,
        onQualityChange = onQualityChange,
        onExportClick = onExportClick,
        onOpenGalleryClick = onOpenGalleryClick,
    ) { info, thumbnailModifier ->
        // exportTick forces overlay to re-read mutated [ImageInfo.jobState] during batch export.
        val job = androidx.compose.runtime.remember(info.uri, exportTick) { info.jobState }
        me.rosuh.easywatermark.ui.save.ExportProgressOverlay(
            jobState = job,
            modifier = thumbnailModifier,
        ) {
            AsyncImage(
                model = info.uri.toUri(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
