package me.rosuh.easywatermark.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.adapter.FuncPanelAdapter
import me.rosuh.easywatermark.base.BaseBindingActivity
import me.rosuh.easywatermark.databinding.ActivityMainBinding
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.model.FuncTitleModel
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.dialog.ChangeLogDialogFragment
import me.rosuh.easywatermark.ui.dialog.CompressImageDialogFragment
import me.rosuh.easywatermark.ui.dialog.EditTextBSDialogFragment
import me.rosuh.easywatermark.ui.dialog.SaveImageBSDialogFragment
import me.rosuh.easywatermark.ui.panel.*
import me.rosuh.easywatermark.utils.*
import kotlin.math.abs


class MainActivity : BaseBindingActivity<ActivityMainBinding>() {

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var pickIconLauncher: ActivityResultLauncher<String>
    private val viewModel: MainViewModel by viewModels()

    private val scope = lifecycleScope

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

    private val funcAdapter by lazy { FuncPanelAdapter(ArrayList(contentFunList)) }

    private lateinit var snapHelper: LinearSnapHelper

    private val vibrateHelper: VibrateHelper by lazy { VibrateHelper.get() }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    @ObsoleteCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        ChangeLogDialogFragment.safetyShow(this@MainActivity.supportFragmentManager)
    }

    private fun registerResultCallback() {
        pickImageLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                handleActivityResult(REQ_CODE_PICK_IMAGE, uri)
            }
        pickIconLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                handleActivityResult(REQ_PICK_ICON, uri)
            }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
    }

    override fun onStart() {
        super.onStart()
        // Accepting shared images from other apps
        if (intent?.action == ACTION_SEND) {
            dealWithImage(intent?.data)
        }
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

    @ObsoleteCoroutinesApi
    private fun initObserver() {
        viewModel.config.observe(this, Observer<WaterMarkConfig> {
            if (it.uri.toString().isEmpty()) {
                return@Observer
            }
            try {
                if (binding.clRoot.currentState != R.id.open_image_start) {
                    setTransition(R.id.launch_end, R.id.open_image_start)
                    binding.ivLogo.stop()
                }
                binding.ivPhoto.config = it
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.updateUri(Uri.parse(""))
            }
        })

        viewModel.tipsStatus.observe(this) { tips ->
            when (tips) {
                is MainViewModel.TipsStatus.None -> {
                    binding.tvDataTips.apply {
                        isInvisible = true
                    }
                }
                is MainViewModel.TipsStatus.Alpha -> {
                    binding.tvDataTips.apply {
                        isVisible = true
                        text = getString(R.string.touch_alpha, tips.values as? Int)
                    }
                }
                is MainViewModel.TipsStatus.Size -> {
                    binding.tvDataTips.apply {
                        isVisible = true
                        text = getString(R.string.touch_size, tips.values as? Int)
                    }
                }
            }
        }

        viewModel.saveState.observe(this) { state ->
            when (state) {
                MainViewModel.State.SaveOk -> {
                    Toast.makeText(this, getString(R.string.tips_save_ok), Toast.LENGTH_SHORT)
                        .show()
                }
                MainViewModel.State.ShareOk -> {
                    Toast.makeText(this, getString(R.string.tips_share_ok), Toast.LENGTH_SHORT)
                        .show()
                }
                MainViewModel.State.Error -> {
                    Toast.makeText(
                        this,
                        "${getString(R.string.tips_error)}: ${state.msg}",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.resetStatus()
                }
                MainViewModel.State.OOMError -> {
                    CompressImageDialogFragment.safetyShow(supportFragmentManager)
                    viewModel.resetStatus()
                }
            }
        }

        viewModel.result.observe(this) {
            Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        // prepare MotionLayout
        binding.clRoot.addTransitionListener(object : SimpleTransitionListener() {
            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
                if (p1 == R.id.launch_end) {
                    binding.ivLogo.start()
                } else {
                    binding.ivLogo.stop()
                }
                if (p1 == R.id.open_image_start) {
                    // auto scroll to correct x-position
                    binding.rvPanel.post {
                        // disable scroll listener
//                        binding.rvPanel.canAutoSelected = false
//                        binding.rvPanel.smoothScrollToPosition(0)
                    }
                }
            }
        })
        setTransition(R.id.launch_start, R.id.launch_end)
        // setting tool bar
        binding.myToolbar.apply {
            navigationIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_logo_tool_bar)
            title = null
            setSupportActionBar(this)
            supportActionBar?.title = null
        }
        // go about page
        binding.ivGoAboutPage.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // setting water image widget
        binding.ivPhoto.apply {
            doOnColorReady {
                scope.launch {
                    applyBgColor(it)
                }
            }
        }
        // pick image button
        binding.ivPickerTips.setOnClickListener {
            preCheckStoragePermission {
                performFileSearch(REQ_CODE_PICK_IMAGE)
            }
        }
        // functional panel in recyclerView
        binding.rvPanel.apply {
            adapter = funcAdapter
            layoutManager = CenterLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)

            snapHelper = LinearSnapHelper().also {
                it.attachToRecyclerView(this)
            }

            onItemClick { _, pos, v ->
                val snapView = snapHelper.findSnapView(binding.rvPanel.layoutManager)
                if (snapView == v) {
                    val item = (this.adapter as FuncPanelAdapter).dataSet[pos]
                    handleFuncItem(item)
                    funcAdapter.selectedPos = pos
                } else {
                    smoothScrollToPosition(pos)
                }
            }

            post {
                // center item x-position
                val padding = (this.width / 2)
                this.setPadding(padding, 0, padding, 0)
            }

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var debounceTs = 0L
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val snapView = snapHelper.findSnapView(binding.rvPanel.layoutManager)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            if (snapView == null
                                || !canAutoSelected
                                || System.currentTimeMillis() - debounceTs < 120
                            ) {
                                canAutoSelected = true
                                return
                            }
                            debounceTs = System.currentTimeMillis()
                            val pos = binding.rvPanel.getChildLayoutPosition(snapView)
                            funcAdapter.selectedPos = pos
                            handleFuncItem(funcAdapter.dataSet[pos])
                            vibrateHelper.doVibrate(snapView)
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) {
                        return
                    }
                    val snapView = snapHelper.findSnapView(binding.rvPanel.layoutManager) ?: return
                    val dX =
                        abs(binding.fcFunDetail.width / 2 - (snapView.left + snapView.right) / 2)

                    if (dX <= 1) {
                        vibrateHelper.doVibrate(snapView)
                    }
                }
            })
        }

        binding.tbToolBar.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    if (tab == null) {
                        return
                    }
                    hideDetailPanel()
                    val adapter = (binding.rvPanel.adapter as? FuncPanelAdapter)
                    when (tab.position) {
                        1 -> {
                            binding.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(styleFunList, 0)
                                handleFuncItem(it.dataSet[0])
                            }
                        }
                        2 -> {
                            binding.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(layoutFunList, 0)
                                handleFuncItem(it.dataSet[0])
                            }
                        }
                        else -> {
                            val curPos =
                                if (binding.ivPhoto.config?.markMode == WaterMarkConfig.MarkMode.Text) 0 else 1
                            adapter?.also {
                                it.seNewData(contentFunList, curPos)
                            }
//                            binding.rvPanel.canAutoSelected = false
//                            binding.rvPanel.smoothScrollToPosition(curPos)
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
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

    private fun applyBgColor(palette: Palette) {
        val color =
            palette.darkMutedSwatch?.rgb ?: ContextCompat.getColor(this, R.color.colorSecondary)
        binding.ivPhoto.setBackgroundColor(color)
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
                ColorFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.Alpha -> {
                AlphaPbFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.Degree -> {
                DegreePbFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.TextStyle -> {
                TextStyleFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.Vertical -> {
                VerticalPbFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.Horizon -> {
                HorizonPbFragment.replaceShow(this, binding.fcFunDetail.id)
            }
            FuncTitleModel.FuncType.TextSize -> {
                TextSizePbFragment.replaceShow(this, binding.fcFunDetail.id)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
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
        val mime = "image/*"
        val result = kotlin.runCatching {
            when (requestCode) {
                REQ_CODE_PICK_IMAGE -> {
                    pickImageLauncher.launch(mime)
                }
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

    private fun dealWithImage(uri: Uri?) {
        if (FileUtils.isImage(this.contentResolver, uri)) {
            viewModel.updateUri(uri!!)
            takePersistableUriPermission(uri)
        } else {
            Toast.makeText(
                this,
                getString(R.string.tips_choose_other_file_type),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleActivityResult(requestCode: Int, uri: Uri?) {
        if (uri == null) {
            Toast.makeText(
                this,
                getString(R.string.tips_do_not_choose_image),
                Toast.LENGTH_SHORT
            ).show()
            if (requestCode == REQ_PICK_ICON) {
                manuallySelectedItem(0)
            }
            return
        }
        when (requestCode) {
            REQ_CODE_PICK_IMAGE -> {
                dealWithImage(uri)
            }
            REQ_PICK_ICON -> {
                if (FileUtils.isImage(this.contentResolver, uri)) {
                    viewModel.updateIcon(uri)
                    takePersistableUriPermission(uri)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.tips_choose_other_file_type),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun manuallySelectedItem(pos: Int) {
        binding.rvPanel.canAutoSelected = false
        funcAdapter.selectedPos = pos
        binding.rvPanel.smoothScrollToPosition(pos)
    }

    /**
     * Try to get the permission without timeout.
     */
    private fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags: Int =
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBackPressed() {
        if (binding.clRoot.currentState != R.id.open_image_start) {
            super.onBackPressed()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog)
            .setTitle(R.string.dialog_title_exist_confirm)
            .setMessage(R.string.dialog_content_exist_confirm)
            .setNegativeButton(
                R.string.tips_confirm_dialog
            ) { _, _ ->
                setTransition(R.id.launch_start, R.id.launch_end)
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
        hideDetailPanel()
    }

    private fun setTransition(startState: Int, endState: Int) {
        binding.root.post {
            binding.clRoot.setTransition(startState, endState)
            binding.clRoot.transitionToEnd()
        }
    }

    companion object {
        private const val REQ_CODE_PICK_IMAGE: Int = 42
        const val REQ_CODE_REQ_WRITE_PERMISSION: Int = 43
        const val REQ_PICK_ICON: Int = 44
    }
}