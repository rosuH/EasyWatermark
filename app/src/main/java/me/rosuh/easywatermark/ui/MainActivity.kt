package me.rosuh.easywatermark.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.forEach
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ViewInfo
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.adapter.FuncPanelAdapter
import me.rosuh.easywatermark.ui.adapter.PhotoListPreviewAdapter
import me.rosuh.easywatermark.ui.dialog.*
import me.rosuh.easywatermark.ui.panel.*
import me.rosuh.easywatermark.ui.widget.CenterLayoutManager
import me.rosuh.easywatermark.ui.widget.LaunchView
import me.rosuh.easywatermark.utils.FileUtils
import me.rosuh.easywatermark.utils.PickImageContract
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.utils.ktx.*
import me.rosuh.easywatermark.utils.onItemClick


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var pickIconLauncher: ActivityResultLauncher<String>
    private val viewModel: MainViewModel by viewModels()

    private val currentBgColor: Int
        get() = ((launchView.parent as? View?)?.background as? ColorDrawable)?.color ?: colorSurface

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private val contentFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Text,
                getString(R.string.water_mark_mode_text),
                R.drawable.ic_func_text
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Icon,
                getString(R.string.water_mark_mode_image),
                R.drawable.ic_func_sticker
            )
        )
    }

    private val styleFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.TileMode,
                getString(R.string.title_tile_mode),
                R.drawable.ic_tile_mode
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.TextSize,
                getString(R.string.title_text_size),
                R.drawable.ic_func_size
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.TextStyle,
                getString(R.string.title_text_style),
                R.drawable.ic_func_typeface
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Color,
                getString(R.string.title_text_color),
                R.drawable.ic_func_color
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Alpha,
                getString(R.string.style_alpha),
                R.drawable.ic_func_opacity
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Degree,
                getString(R.string.title_text_rotate),
                R.drawable.ic_func_angle
            )
        )
    }

    private val layoutFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Horizon,
                getString(R.string.title_horizon_layout),
                R.drawable.ic_func_layour_horizontal
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Vertical,
                getString(R.string.title_vertical_layout),
                R.drawable.ic_func_layout_vertical
            )
        )
    }

    private val funcAdapter by lazy {
        FuncPanelAdapter(ArrayList(contentFunList)).apply {
            setHasStableIds(true)
        }
    }

    private val photoListPreviewAdapter by lazy { PhotoListPreviewAdapter(this) }

    private val vibrateHelper: VibrateHelper by lazy { VibrateHelper.get() }

    private lateinit var launchView: LaunchView

    private var bgTransformAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MyApp.recoveryMode) {
            setContentView(R.layout.activity_recovery)
            initRecoveryView()
            return
        }
        launchView = LaunchView(this)
        setContentView(launchView)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
            }
        }
        initView()
        initObserver()
        registerResultCallback()
        checkHadCrash()
        // Activity was recycled but dialog still showing in some case?
        SaveImageBSDialogFragment.safetyHide(this@MainActivity.supportFragmentManager)
    }

    private fun initRecoveryView() {
        val tvCrashInfo = findViewById<TextView>(R.id.tv_crash_info).apply {
            with(getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)) {
                val crashInfo = getString(MyApp.KEY_STACK_TRACE, "")
                text = crashInfo
            }
        }
        val btnCopy = findViewById<Button>(R.id.btn_copy).apply {
            setOnClickListener {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(tvCrashInfo.text, tvCrashInfo.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, R.string.copy_success, Toast.LENGTH_SHORT)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, R.string.copy_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        val btnSendEmail = findViewById<Button>(R.id.btn_email).apply {
            setOnClickListener {
                viewModel.extraCrashInfo(this@MainActivity, tvCrashInfo.text.toString())
            }
        }
        val btnTelegram = findViewById<Button>(R.id.btn_telegram).apply {
            setOnClickListener {
                openLink("https://t.me/rosuh")
            }
        }
        val btnStore = findViewById<Button>(R.id.btn_store).apply {
            setOnClickListener {
                openLink(Uri.parse("market://details?id=me.rosuh.easywatermark")) {
                    Toast.makeText(this@MainActivity, R.string.store_not_found, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        findViewById<Button>(R.id.btn_close_recovery_mode).apply {
            setOnClickListener {
                (MyApp.instance as MyApp).launchSuccess()
                Toast.makeText(this@MainActivity, R.string.recovery_mode_closed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (MyApp.recoveryMode) {
            return
        }
    }

    private fun registerResultCallback() {
        pickIconLauncher =
            registerForActivityResult(PickImageContract()) { uri: Uri? ->
                handleActivityResult(REQ_PICK_ICON, listOf(uri))
            }
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                return@registerForActivityResult
            }
            Toast.makeText(
                this,
                getString(R.string.request_permission_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
    }

    override fun onStart() {
        super.onStart()
        // Accepting shared images from other apps
        if (intent?.action == ACTION_SEND && intent?.data != null) {
            dealWithImage(listOf(intent?.data!!))
        }
    }

    override fun onResume() {
        super.onResume()
        if (MyApp.recoveryMode) {
            return
        }
        lifecycleScope.launch {
            delay(1000)
            if (this@MainActivity.isFinishing) {
                return@launch
            }
            (MyApp.instance as? MyApp?)?.launchSuccess()
        }
    }

    override fun onDestroy() {
        bgTransformAnimator?.cancel()
        super.onDestroy()
    }

    private fun checkHadCrash() {
        with(getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)) {
            val isCrash = getBoolean(MyApp.KEY_IS_CRASH, false)
            if (!isCrash) {
                return@with
            }
            val crashInfo = getString(MyApp.KEY_STACK_TRACE, "")
            edit {
                putBoolean(MyApp.KEY_IS_CRASH, false)
                putString(MyApp.KEY_STACK_TRACE, "")
            }
            showCrashDialog(crashInfo)
        }
    }

    private fun showCrashDialog(crashInfo: String?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tips_tip_title)
            .setMessage(R.string.msg_crash)
            .setNegativeButton(
                R.string.tips_cancel_dialog
            ) { dialog, _ -> dialog?.dismiss() }
            .setPositiveButton(
                R.string.crash_mail
            ) { dialog, _ ->
                viewModel.extraCrashInfo(this, crashInfo)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun initObserver() {
        lifecycleScope.launch {
            viewModel.uiStateFlow.flowWithLifecycle(
                this@MainActivity.lifecycle,
                Lifecycle.State.STARTED
            ).collect {
                if (it == UiState.GoEditDialog) {
                    TextWatermarkBSDFragment.safetyShow(supportFragmentManager)
                }
            }
        }
        viewModel.waterMark.observe(this) {
            if (it == null) {
                return@observe
            }
            Log.i("initObserver", "$it")
            launchView.post {
                launchView.ivPhoto.config = it
            }
            if (it.markMode == WaterMarkRepository.MarkMode.Image && launchView.tabLayout.selectedTabPosition == 0) {
                hideDetailPanel()
            }
            viewModel.resetJobStatus()
        }
        viewModel.selectedImage.observe(this) {
            if (it == null || it.uri.toString().isBlank()) {
                return@observe
            }
            try {
                val isAnimating = launchView.toEditorMode()
                if (isAnimating) {
                    launchView.ivPhoto.updateUri(true, it)
                    selectTab(0)
                } else {
                    launchView.ivPhoto.updateUri(false, it)
                }
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.selectImage(Uri.EMPTY)
            }
        }
        viewModel.imageList.observe(this) {
            photoListPreviewAdapter.selectedPos = viewModel.nextSelectedPos
            photoListPreviewAdapter.submitList(it.first.toList()) {
                if (it.second.not()) {
                    return@submitList
                }
                launchView.rvPhotoList.apply {
                    post { smoothScrollToPosition(0) }
                }
            }
        }

        viewModel.saveResult.observe(this) {
            if (it.isFailure()) {
                when (it.code) {
                    MainViewModel.TYPE_ERROR_SAVE_OOM -> {
                        toast(getString(R.string.error_save_oom))
                        CompressImageDialogFragment.safetyShow(supportFragmentManager)
                        viewModel.resetJobStatus()
                    }
                    MainViewModel.TYPE_ERROR_FILE_NOT_FOUND -> toast(getString(R.string.error_file_not_found))
                    MainViewModel.TYPE_ERROR_NOT_IMG -> toast(getString(R.string.error_not_img))
                    else -> toast("${getString(R.string.tips_error)}: ${it.message}")
                }
                viewModel.resetJobStatus()
            } else {
                toast(it.message)
            }
        }

        viewModel.colorPalette.observe(this) { palette ->
            val bgColor = palette.bgColor(this)
            val titleTextColor = palette.titleTextColor(this)

            bgTransformAnimator = currentBgColor.toColor(bgColor) {
                val c = it.animatedValue as Int
                if (launchView.isEdit()) {
                    doApplyBgChanged(c)
                } else {
                    doApplyBgChanged()
                }
            }
            funcAdapter.textColor.toColor(titleTextColor) {
                val c = it.animatedValue as Int
                funcAdapter.applyTextColor(c)
                launchView.tabLayout.setTabTextColors(c, this.colorPrimary)
                launchView.toolbar.menu.forEach { menuItem ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        menuItem.iconTintList = ColorStateList.valueOf(c)
                    } else {
                        menuItem.icon?.setTint(c)
                    }
                }
            }
        }
    }

    private fun Context.toast(msg: String?) {
        if (msg.isNullOrBlank()) return
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        doApplyBgChanged()
        // prepare MotionLayout
        launchView.setListener {
            onModeChange { _, newMode ->
                when (newMode) {
                    LaunchView.ViewMode.Editor -> {
                        launchView.logoView.stop()
                    }
                    LaunchView.ViewMode.LaunchMode -> {
                        launchView.logoView.start()
                    }
                }
            }
        }
        // setting tool bar
        launchView.toolbar.apply {
            navigationIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_logo_tool_bar)
            title = null
            setSupportActionBar(this)
            supportActionBar?.title = null
        }
        // go about page
        launchView.ivGoAboutPage.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // pick image button
        launchView.ivSelectedPhotoTips.setOnClickListener {
            checkReadingPermission(requestPermissionLauncher) {
                performFileSearch(REQ_CODE_PICK_IMAGE)
            }
        }
        // setting bg
        launchView.ivPhoto.apply {
            onBgReady { palette ->
                viewModel.updateColorPalette(palette)
            }
            onOffsetChanged {
                viewModel.updateOffset(it)
            }
            onScaleEnd {
                viewModel.updateTextSize(it)
            }
        }
        // functional panel in recyclerView
        launchView.rvPanel.apply {
            adapter = funcAdapter
            setHasFixedSize(true)
            layoutManager = CenterLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            onItemClick { _, pos, v ->
                val snapView = snapHelper.findSnapView(launchView.rvPanel.layoutManager)
                if (snapView == v) {
                    val item = (this.adapter as FuncPanelAdapter).dataSet[pos]
                    handleFuncItem(item)
                    funcAdapter.selectedPos = pos
                } else {
                    smoothScrollToPosition(pos)
                }
            }

            onSnapViewPreview { snapView, _ ->
                vibrateHelper.doVibrate(snapView)
            }

            onSnapViewSelected { snapView, pos ->
                funcAdapter.selectedPos = pos
                handleFuncItem(funcAdapter.dataSet[pos])
                vibrateHelper.doVibrate(snapView)
            }

            post {
                canAutoSelected = false
                scrollToPosition(0)
                canAutoSelected = true
            }
        }
        // image list
        launchView.rvPhotoList.apply {
            enableBorder = true
            adapter = photoListPreviewAdapter
            setHasFixedSize(true)
            layoutManager =
                CenterLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false).apply {
                    onStartSmoothScroll {
                        canTouch = false
                    }
                    onStopSmoothScroll {
                        canTouch = true
                    }
                }

            photoListPreviewAdapter.onRemove { imageInfo ->
                viewModel.removeImage(imageInfo, photoListPreviewAdapter.selectedPos)
            }

            onItemClick { _, pos, v ->
                val snapView = snapHelper.findSnapView(launchView.rvPanel.layoutManager)
                if (snapView != v) {
                    smoothScrollToPosition(pos)
                }
            }

            onSnapViewPreview { snapView, _ ->
                vibrateHelper.doVibrate(snapView)
            }

            onSnapViewSelected { snapView, pos ->
                photoListPreviewAdapter.selectedPos = pos
                val uri = photoListPreviewAdapter.getItem(pos)?.uri ?: return@onSnapViewSelected
                viewModel.selectImage(uri)
                vibrateHelper.doVibrate(snapView)
            }
        }

        launchView.tabLayout.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    if (tab == null) {
                        return
                    }
                    hideDetailPanel()
                    vibrateHelper.doVibrate(this@apply)
                    val adapter = (launchView.rvPanel.adapter as? FuncPanelAdapter)
                    when (tab.position) {
                        0 -> {
                            val curPos =
                                if (launchView.ivPhoto.config?.markMode == WaterMarkRepository.MarkMode.Image) 1 else 0
                            if (curPos == 0) {
                                launchView.rvPanel.smoothScrollToPosition(0)
                                adapter?.also {
                                    it.seNewData(contentFunList, 0)
                                    post { handleFuncItem(it.dataSet[0]) }
                                }
                            } else {
                                hideDetailPanel()
                                adapter?.seNewData(contentFunList, curPos)
                                manuallySelectedItem(curPos)
                            }
                        }
                        2 -> {
                            launchView.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(layoutFunList, 0)
                                post { handleFuncItem(it.dataSet[0]) }
                            }
                        }
                        else -> {
                            launchView.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(styleFunList, 0)
                                post { handleFuncItem(it.dataSet[0]) }
                            }
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    if (tab?.position == 0) {
                        handleFuncItem(contentFunList[0])
                    }
                }
            })
        }
    }

    private fun hideDetailPanel() {
        commitWithAnimation {
            supportFragmentManager.fragments.forEach {
                remove(it)
            }
        }
    }

    private fun handleFuncItem(item: FuncTitleModel) {
        Log.i("handleFuncItem", "item = $item")
        when (item.type) {
            FuncTitleModel.FuncType.Text -> {
                TextContentDisplayFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Icon -> {
                checkReadingPermission(requestPermissionLauncher) {
                    performFileSearch(REQ_PICK_ICON)
                }
            }
            FuncTitleModel.FuncType.Color -> {
                ColorFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Alpha -> {
                AlphaPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Degree -> {
                DegreePbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TextStyle -> {
                TextStyleFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Vertical -> {
                VerticalPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Horizon -> {
                HorizonPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TextSize -> {
                TextSizePbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TileMode -> {
                TileModeFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
        }
    }

    private fun setStatusBarColor(color: Int, isInEditMode: Boolean) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val systemUiAppearance = if (isInEditMode && this.isNight()) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                systemUiAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            val systemUiVisibilityFlags = if (!isInEditMode && !this.isNight()) {
                window.decorView.systemUiVisibility or SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            window.decorView.systemUiVisibility = systemUiVisibilityFlags
        }
        window.statusBarColor = color
        window.findViewById<View>(android.R.id.content)?.foreground = null
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        R.id.action_pick -> {
            checkReadingPermission(requestPermissionLauncher) {
                performFileSearch(REQ_CODE_PICK_IMAGE)
            }
            true
        }

        R.id.action_save -> {
            SaveImageBSDialogFragment.safetyShow(supportFragmentManager)
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch(requestCode: Int) {
        if (requestCode == REQ_PICK_ICON) {
            val mime = "image/*"
            val result = kotlin.runCatching {
                when (requestCode) {
                    REQ_PICK_ICON -> {
                        pickIconLauncher.launch(mime)
                    }
                }
            }

            if (result.isFailure) {
                Toast.makeText(
                    this,
                    getString(R.string.tips_not_app_can_open_images),
                    Toast.LENGTH_LONG
                ).show()
                Log.i("performFileSearch", result.exceptionOrNull()?.message ?: "No msg provided")
            }
        } else {
            GalleryFragment().apply {
                launchView.logoView.stop()
                doOnDismiss {
                    launchView.logoView.start()
                }
                show(supportFragmentManager, "GalleryFragment")
            }
        }
    }

    private fun dealWithImage(uri: List<Uri>) {
        if (FileUtils.isImage(this.contentResolver, uri.first())) {
            viewModel.updateImageList(uri)
        } else {
            Toast.makeText(
                this,
                getString(R.string.tips_choose_other_file_type),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleActivityResult(requestCode: Int, list: List<Uri?>?) {
        val finalList = list?.filterNotNull()?.filter {
            FileUtils.isImage(this.contentResolver, it)
        } ?: emptyList()
        if (finalList.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.tips_do_not_choose_image),
                Toast.LENGTH_SHORT
            ).show()
            if (requestCode == REQ_PICK_ICON && viewModel.waterMark.value?.markMode == WaterMarkRepository.MarkMode.Text) {
                manuallySelectedItem(0)
            }
            return
        }
        when (requestCode) {
            REQ_CODE_PICK_IMAGE -> {
                Log.i(MainActivity::class.simpleName, finalList.toTypedArray().contentToString())
                dealWithImage(finalList)
            }
            REQ_PICK_ICON -> {
                viewModel.updateIcon(finalList.first())
            }
        }
    }

    private fun manuallySelectedItem(pos: Int) {
        launchView.rvPanel.canAutoSelected = false
        funcAdapter.selectedPos = pos
        launchView.rvPanel.scrollToPosition(pos)
        launchView.rvPanel.canAutoSelected = true
    }

    override fun onBackPressed() {
        if (MyApp.recoveryMode) {
            super.onBackPressed()
            return
        }
        if (launchView.mode == LaunchView.ViewMode.LaunchMode) {
            super.onBackPressed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_exist_confirm)
            .setMessage(R.string.dialog_content_exist_confirm)
            .setNegativeButton(
                R.string.tips_confirm_dialog
            ) { _, _ ->
                resetView()
            }
            .setPositiveButton(
                R.string.dialog_cancel_exist_confirm
            ) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun resetView() {
        launchView.toLaunchMode()
        viewModel.resetJobStatus()
        viewModel.clearData()
        launchView.ivPhoto.reset()
        bgTransformAnimator?.cancel()
        TextContentDisplayFragment.remove(this)
        doApplyBgChanged()
        hideDetailPanel()
    }

    private fun doApplyBgChanged(
        color: Int = ContextCompat.getColor(
            this,
            R.color.md_theme_dark_background
        )
    ) {
        (launchView.parent as? View?)?.setBackgroundColor(color)
        window?.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.navigationBarDividerColor = Color.TRANSPARENT
        }
        setStatusBarColor(color, true)
    }

    private fun selectTab(index: Int) {
        launchView.tabLayout.getTabAt(index).let {
            launchView.tabLayout.selectTab(it)
        }
    }

    fun getImageList(): List<ImageInfo> {
        return photoListPreviewAdapter.data
    }

    fun getImageViewInfo(): ViewInfo {
        return ViewInfo.from(launchView.ivPhoto)
    }

    fun requestPermission(block: () -> Unit) {
        checkWritingPermission(requestPermissionLauncher, grant = block)
    }

    companion object {
        private const val REQ_CODE_PICK_IMAGE: Int = 42
        const val REQ_CODE_REQ_WRITE_PERMISSION: Int = 43
        const val REQ_PICK_ICON: Int = 44
    }
}
