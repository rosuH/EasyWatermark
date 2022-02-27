package me.rosuh.easywatermark.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.*
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
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
import me.rosuh.easywatermark.utils.*
import me.rosuh.easywatermark.utils.ktx.*
import java.util.*
import kotlin.collections.ArrayList


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var pickIconLauncher: ActivityResultLauncher<String>
    private val viewModel: MainViewModel by viewModels()

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
        FuncPanelAdapter(ArrayList(styleFunList)).apply {
            setHasStableIds(true)
        }
    }

    private val photoListPreviewAdapter by lazy { PhotoListPreviewAdapter(this) }

    private val vibrateHelper: VibrateHelper by lazy { VibrateHelper.get() }

    private lateinit var launchView: LaunchView

    private var bgTransformAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, launchView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }

    }

    // Shows the system bars by removing all the flags
    // except for the ones that make the content appear under the system bars.
    private fun showSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(
                window,
                launchView
            ).show(WindowInsetsCompat.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun registerResultCallback() {
        pickIconLauncher =
            registerForActivityResult(PickImageContract()) { uri: Uri? ->
                handleActivityResult(REQ_PICK_ICON, listOf(uri))
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
        MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog)
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
        viewModel.waterMark.observe(this) {
            if (it == null) {
                return@observe
            }
            launchView.post {
                launchView.ivPhoto.config = it
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
                    launchView.ivPhoto.updateUri(isAnimating, it)
                    selectTab(0)
                } else {
                    launchView.ivPhoto.updateUri(isAnimating, it)
                }
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.selectImage(Uri.EMPTY)
            }
        }
        viewModel.imageList.observe(this) {
            photoListPreviewAdapter.selectedPos = viewModel.nextSelectedPos
            photoListPreviewAdapter.submitList(it.first) {
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

            bgTransformAnimator =
                ((launchView.ivPhoto.background as? ColorDrawable)?.color ?: Color.BLACK).toColor(
                    bgColor
                ) {
                    val c = it.animatedValue as Int
                    if (launchView.isEdit()) {
                        launchView.rvPanel.setBackgroundColor(it.animatedValue as Int)
                        setActivityBackground(c)
                        setStatusBarColor(c, isInEditMode = true)
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
                        menuItem.icon.setTint(c)
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
        setActivityBackground(ContextCompat.getColor(this, R.color.md_theme_dark_background))
        setStatusBarColor(ContextCompat.getColor(this, R.color.md_theme_dark_background), false)
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
            preCheckStoragePermission {
                performFileSearch(REQ_CODE_PICK_IMAGE)
            }
        }
        // setting bg
        launchView.ivPhoto.apply {
            onBgReady { palette ->
                viewModel.updateColorPalette(palette)
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

            post {
                canAutoSelected = false
                scrollToPosition(0)
                canAutoSelected = true
            }
        }

        launchView.tabLayout.apply {
            post {
                selectTab(0)
            }
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
                            launchView.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(styleFunList, 0)
                                post { handleFuncItem(it.dataSet[0]) }
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
                            val curPos =
                                if (launchView.ivPhoto.config?.markMode == WaterMarkRepository.MarkMode.Text) 0 else 1
                            adapter?.seNewData(contentFunList, curPos)
                            manuallySelectedItem(curPos)
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    val adapter = (launchView.rvPanel.adapter as? FuncPanelAdapter)
                    adapter?.also {
                        post { handleFuncItem(it.dataSet[0]) }
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
                EditTextBSDialogFragment.safetyShow(supportFragmentManager)
            }
            FuncTitleModel.FuncType.Icon -> {
                preCheckStoragePermission {
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
            window.insetsController?.setSystemBarsAppearance(systemUiAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            val systemUiVisibilityFlags = if (!isInEditMode && !this.isNight()) {
                window.decorView.systemUiVisibility or SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            window.decorView.systemUiVisibility = systemUiVisibilityFlags
        }
        window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.findViewById<View>(android.R.id.content)?.foreground = null
        }
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
            preCheckStoragePermission {
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
                    getString(R.string.tips_not_app_can_open_imaegs),
                    Toast.LENGTH_LONG
                ).show()
                Log.i("performFileSearch", result.exceptionOrNull()?.message ?: "No msg provided")
            }
        } else {
            GalleryFragment().show(supportFragmentManager, "GalleryFragment")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_CODE_REQ_WRITE_PERMISSION -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        getString(R.string.request_permission_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
        if (finalList.isNullOrEmpty()) {
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
        if (launchView.mode == LaunchView.ViewMode.LaunchMode) {
            super.onBackPressed()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog)
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
        (launchView.background as? ColorDrawable?)?.color?.toColor(
            ContextCompat.getColor(this, R.color.md_theme_dark_background)
        ) {
            val c = it.animatedValue as Int
            setActivityBackground(c)
            setStatusBarColor(c, isInEditMode = false)
        }
        hideDetailPanel()
    }

    private fun setActivityBackground(color: Int){
        (launchView.parent as? View?)?.setBackgroundColor(color)
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

    companion object {
        private const val REQ_CODE_PICK_IMAGE: Int = 42
        const val REQ_CODE_REQ_WRITE_PERMISSION: Int = 43
        const val REQ_PICK_ICON: Int = 44
    }
}
