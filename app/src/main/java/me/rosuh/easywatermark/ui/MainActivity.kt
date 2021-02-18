package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewConfigurationCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.adapter.FuncPanelAdapter
import me.rosuh.easywatermark.base.BasePBFragment
import me.rosuh.easywatermark.databinding.ActivityMainBinding
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.ktx.dp
import me.rosuh.easywatermark.ktx.inflate
import me.rosuh.easywatermark.model.FuncTitleModel
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.dialog.ChangeLogDialogFragment
import me.rosuh.easywatermark.ui.dialog.CompressImageDialogFragment
import me.rosuh.easywatermark.ui.dialog.EditTextBSDialogFragment
import me.rosuh.easywatermark.ui.dialog.SaveImageBSDialogFragment
import me.rosuh.easywatermark.ui.panel.*
import me.rosuh.easywatermark.utils.FileUtils
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.utils.onItemClick
import me.rosuh.easywatermark.widget.SpaceHeaderItemDecoration
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private val binding by inflate<ActivityMainBinding>()

    private val viewModel: MainViewModel by viewModels()

    private val scope = MainScope()

    private val contentFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Text,
                getString(R.string.water_mark_mode_text),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Icon,
                getString(R.string.water_mark_mode_image),
                R.drawable.ic_bug_report
            )
        )
    }

    private val styleFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.TextSize,
                getString(R.string.title_text_size),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Color,
                getString(R.string.title_text_color),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Alpha,
                getString(R.string.style_alpha),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Degree,
                getString(R.string.title_text_rotate),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.TextStyle,
                getString(R.string.title_text_style),
                R.drawable.ic_bug_report
            )
        )
    }

    private val layoutFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Horizon,
                getString(R.string.title_horizon_layout),
                R.drawable.ic_bug_report
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Vertical,
                getString(R.string.title_vertical_layout),
                R.drawable.ic_bug_report
            )
        )
    }

    private lateinit var snapHelper: LinearSnapHelper

    @ObsoleteCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
            }
        }
        setContentView(R.layout.activity_main)
        setSupportActionBar(binding.myToolbar)
        initView()
        initObserver()
        scope.launch {
            binding.clRoot.addTransitionListener(object : MotionLayout.TransitionListener {
                override fun onTransitionStarted(p0: MotionLayout?, p1: Int, p2: Int) {
//                    Log.i("MainActivity", "onTransitionStarted, p1 = $p1, p2 = $p2")
                }

                override fun onTransitionChange(p0: MotionLayout?, p1: Int, p2: Int, p3: Float) {
//                    Log.i("MainActivity", "onTransitionChange, p1 = $p1, p2 = $p2")
                }

                override fun onTransitionCompleted(p0: MotionLayout?, id: Int) {
                    if (id == R.id.launch_end) {
                        binding.ivLogo.start()
                    } else {
                        binding.ivLogo.stop()
                    }
                }

                override fun onTransitionTrigger(
                    p0: MotionLayout?,
                    p1: Int,
                    p2: Boolean,
                    p3: Float
                ) {
//                    Log.i("MainActivity", "onTransitionTrigger, p1 = $p1, p2 = $p2")
                }

            })
            binding.clRoot.setTransition(R.id.transition_launch)
            binding.clRoot.transitionToEnd()
            checkHadCrash()
            // Activity was recycled but dialog still showing in some case?
            SaveImageBSDialogFragment.safetyHide(this@MainActivity.supportFragmentManager)
            ChangeLogDialogFragment.safetyShow(this@MainActivity.supportFragmentManager)
            // Accepting gallery shared images
            if (intent?.action == ACTION_SEND) {
                dealWithImage(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_SEND) {
            dealWithImage(intent)
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onPause() {
        super.onPause()
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
                binding.clRoot.setTransition(R.id.transition_open_image)
                binding.clRoot.transitionToEnd()
                binding.ivPhoto.config = it
                binding.ivLogo.stop()
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.updateUri(Uri.parse(""))
            }
        })

        viewModel.tipsStatus.observe(this, { tips ->
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
        })

        viewModel.saveState.observe(this, { state ->
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
        })
    }

    private fun initView() {
        binding.myToolbar.apply {
            navigationIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_logo_tool_bar)
            title = null
        }
        binding.ivGoAboutPage.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.ivPhoto.apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                private val verticalFac =
                    ViewConfigurationCompat.getScaledHoverSlop(ViewConfiguration.get(this@MainActivity))
                private val leftArea: ClosedFloatingPointRange<Float>
                    get() {
                        return 0f..(binding.ivPhoto.width / 2).toFloat()
                    }
                private val rightArea: ClosedFloatingPointRange<Float>
                    get() {
                        return (binding.ivPhoto.width / 2).toFloat()..(binding.ivPhoto.width.toFloat())
                    }

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    when (event?.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.x
                            startY = event.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - startX
                            val dy = event.y - startY
                            if (abs(dx) > verticalFac) {
                                return false
                            }
                            when {
                                (event.x in leftArea) -> {
                                    viewModel.updateAlphaBy(dy / 2)
                                }
                                (event.x in rightArea) -> {
                                    viewModel.updateTextSizeBy(dy / 5)
                                }
                            }
                            startX = event.x
                            startY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            performClick()
                            scope.launch {
                                delay(300)
                                val isAlphaZero = (config?.alpha ?: 0) == 0
                                val isTextSize = (config?.textSize ?: 0) == 0
                                viewModel.updateTips(MainViewModel.TipsStatus.None(isAlphaZero || isTextSize))
                            }
                        }
                    }
                    return true
                }

            })
        }
        binding.ivPickerTips.setOnClickListener {
            performFileSearch(READ_REQUEST_CODE)
        }

        binding.rvPanel.apply {
            adapter = FuncPanelAdapter(ArrayList(contentFunList))
//            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
//                override fun onPageSelected(position: Int) {
//                    super.onPageSelected(position)
//                    vibrateHelper.doVibrate()
//                    Log.i("MainActivity", "onPageSelected, pos = $position")
//                }
//            })
//            val scaleFactor = 1f
//            val pageOffset = 0.dp
//            val pageMargin = (-120).dp
//            setPageTransformer { page, position ->
//                page.scaleY = scaleFactor
//                page.scaleX = scaleFactor
//                val myOffset: Float =
//                    position * -(2 * pageOffset + pageMargin)
//                when {
//                    position < -1 -> {
//                        page.translationX = -myOffset
//                    }
//                    position <= 1 -> {
//                        page.translationX = myOffset
//                    }
//                    else -> {
//                        page.translationX = myOffset
//                    }
//                }
//            }
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            onItemClick { recyclerView, position, v ->
                smoothScrollBy(
                    v.left - (recyclerView.width / 2) + v.width / 2,
                    0,
                    AccelerateDecelerateInterpolator(),
                    250
                )
                val item = (recyclerView.adapter as? FuncPanelAdapter)?.dataSet?.get(position)
                    ?: return@onItemClick
                handleFuncItem(item)
            }
            post {
                if (itemDecorationCount == 0) {
                    addItemDecoration(SpaceHeaderItemDecoration(binding.root.width / 2))
                }
            }
            snapHelper = LinearSnapHelper().also {
                it.attachToRecyclerView(this)
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var view: View? = null
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val snapView = snapHelper.findSnapView(binding.rvPanel.layoutManager)
                        if (snapView == view || snapView == null) {
                            return
                        }
                        view = snapView
                        val pos = binding.rvPanel.getChildLayoutPosition(snapView)
                        (recyclerView.adapter as? FuncPanelAdapter)?.dataSet?.get(pos)
                            ?.let { handleFuncItem(it) }
                    }
                }
            })
        }

        val fragmentArray = arrayOf(
            initFragments(binding.vpControlPanel, 2, ContentFragment.newInstance()),
            initFragments(binding.vpControlPanel, 1, StyleFragment.newInstance()),
            initFragments(binding.vpControlPanel, 0, LayoutFragment.newInstance())
        )


        val pagerAdapter = ControlPanelPagerAdapter(this, fragmentArray)
        binding.vpControlPanel.apply {
            isUserInputEnabled = false
            offscreenPageLimit = 2
            adapter = pagerAdapter
        }
        TabLayoutMediator(binding.tbToolBar, binding.vpControlPanel) { tab, position ->
            when (position) {
                0 -> {
                    tab.text = getString(R.string.title_content)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_text_title)
                }
                1 -> {
                    tab.text = getString(R.string.title_style)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_style_title)
                }
                2 -> {
                    tab.text = getString(R.string.title_layout)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_layout_title)
                }
            }
        }.attach()
        binding.tbToolBar.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    if (tab == null) {
                        return
                    }
                    val list = when (tab.position) {
                        1 -> {
                            styleFunList
                        }
                        2 -> {
                            layoutFunList
                        }
                        else -> {
                            contentFunList
                        }
                    }
                    hideDetailPanel()
                    (binding.rvPanel.adapter as? FuncPanelAdapter)?.seNewData(list)
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
    }

    private fun hideDetailPanel() {
        supportFragmentManager.commit {
            for (fragment in supportFragmentManager.fragments) {
                remove(fragment!!)
            }
        }
    }

    private fun handleFuncItem(item: FuncTitleModel) {
        when (item.type) {
            FuncTitleModel.FuncType.Text -> {
                EditTextBSDialogFragment.safetyShow(supportFragmentManager)
            }
            FuncTitleModel.FuncType.Icon -> {
                performFileSearch(ICON_REQUEST_CODE)
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
            performFileSearch(READ_REQUEST_CODE)
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
    fun performFileSearch(requestCode: Int) {
        if (!viewModel.isPermissionGrated(this)) {
            viewModel.requestPermission(this)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val result = kotlin.runCatching {
            startActivityForResult(
                intent,
                requestCode
            )
        }
        if (result.isFailure) {
            Toast.makeText(
                this,
                getString(R.string.tips_not_app_can_open_imaegs),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            WRITE_PERMISSION_REQUEST_CODE -> {
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

    private fun dealWithImage(intent: Intent?) {
        var uri = if (intent?.type?.startsWith("image") == true) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        } else {
            intent?.data
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(
                this,
                getString(R.string.tips_do_not_choose_image),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (requestCode) {
            READ_REQUEST_CODE -> {
                dealWithImage(data)
            }
            ICON_REQUEST_CODE -> {
                val uri = data?.data
                if (FileUtils.isImage(this.contentResolver, uri)) {
                    viewModel.updateIcon(uri!!)
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

    /**
     * Try to get the permission without timeout.
     */
    private fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags: Int = intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class ControlPanelPagerAdapter(
        fa: FragmentActivity,
        var fragmentArray: Array<Fragment>
    ) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = fragmentArray.size

        override fun createFragment(position: Int): Fragment = fragmentArray[position]
    }

    private fun initFragments(vp: ViewPager2, pos: Int, defaultFragment: Fragment): Fragment {
        val tag = "android:switcher:" + vp.id + ":" + pos
        return supportFragmentManager.findFragmentByTag(tag) ?: defaultFragment
    }

    override fun onBackPressed() {
        if (binding.clRoot.currentState == R.id.launch_end) {
            super.onBackPressed()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog)
            .setTitle(R.string.dialog_title_exist_confirm)
            .setMessage(R.string.dialog_content_exist_confirm)
            .setNegativeButton(
                R.string.tips_confirm_dialog
            ) { _, _ ->
                binding.clRoot.setTransition(R.id.launch_start, R.id.launch_end)
                binding.clRoot.transitionToState(R.id.launch_end)
            }
            .setPositiveButton(
                R.string.dialog_cancel_exist_confirm
            ) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val READ_REQUEST_CODE: Int = 42
        const val WRITE_PERMISSION_REQUEST_CODE: Int = 43
        const val ICON_REQUEST_CODE: Int = 44
    }
}