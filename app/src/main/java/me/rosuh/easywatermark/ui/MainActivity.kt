package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewConfigurationCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.dialog.ChangeLogDialogFragment
import me.rosuh.easywatermark.ui.dialog.CompressImageDialogFragment
import me.rosuh.easywatermark.ui.dialog.SaveImageBSDialogFragment
import me.rosuh.easywatermark.ui.panel.ContentFragment
import me.rosuh.easywatermark.ui.panel.LayoutFragment
import me.rosuh.easywatermark.ui.panel.StyleFragment
import me.rosuh.easywatermark.utils.FileUtils
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val scope = MainScope()

    @ObsoleteCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        scope.launch {
            initView()
            initObserver()
            cl_root.setTransition(R.id.transition_launch)
            cl_root.transitionToEnd()
            checkHadCrash()
            // Activity was recycled but dialog still showing in some case?
            SaveImageBSDialogFragment.safetyHide(this@MainActivity.supportFragmentManager)
            ChangeLogDialogFragment.safetyShow(this@MainActivity.supportFragmentManager)
        }
    }

    override fun onStart() {
        super.onStart()
        iv_logo.start()
    }

    override fun onPause() {
        super.onPause()
        iv_logo.stop()
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
                cl_root.setTransition(R.id.transition_open_image)
                cl_root.transitionToEnd()
                iv_photo?.config = it
                iv_logo.stop()
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.updateUri(Uri.parse(""))
            }
        })

        viewModel.tipsStatus.observe(this, { tips ->
            when (tips) {
                is MainViewModel.TipsStatus.None -> {
                    tv_data_tips.apply {
                        isInvisible = true
                    }
                }
                is MainViewModel.TipsStatus.Alpha -> {
                    tv_data_tips.apply {
                        isVisible = true
                        text = getString(R.string.touch_alpha, tips.values as? Int)
                    }
                }
                is MainViewModel.TipsStatus.Size -> {
                    tv_data_tips.apply {
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
        my_toolbar.apply {
            navigationIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_logo_tool_bar)
            title = null
        }
        iv_go_about_page.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        iv_photo.apply {

            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                private val verticalFac =
                    ViewConfigurationCompat.getScaledHoverSlop(ViewConfiguration.get(this@MainActivity))
                private val leftArea: ClosedFloatingPointRange<Float>
                    get() {
                        return 0f..(iv_photo.width / 2).toFloat()
                    }
                private val rightArea: ClosedFloatingPointRange<Float>
                    get() {
                        return (iv_photo.width / 2).toFloat()..(iv_photo.width.toFloat())
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
        iv_picker_tips.setOnClickListener {
            performFileSearch(READ_REQUEST_CODE)
        }

        val fragmentArray = arrayOf(
            initFragments(vp_control_panel, 0, LayoutFragment.newInstance()),
            initFragments(vp_control_panel, 1, StyleFragment.newInstance()),
            initFragments(vp_control_panel, 2, ContentFragment.newInstance())
        )


        val pagerAdapter = ControlPanelPagerAdapter(this, fragmentArray)
        vp_control_panel.apply {
            isUserInputEnabled = false
            offscreenPageLimit = 2
            adapter = pagerAdapter
        }
        TabLayoutMediator(tb_tool_bar, vp_control_panel) { tab, position ->
            when (position) {
                0 -> {
                    tab.text = getString(R.string.title_layout)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_layout_title)
                }
                1 -> {
                    tab.text = getString(R.string.title_style)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_style_title)
                }
                2 -> {
                    tab.text = getString(R.string.title_content)
                    tab.icon = ContextCompat.getDrawable(this, R.drawable.ic_text_title)
                }
            }
        }.attach()
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
                val uri = data?.data
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
        val takeFlags: Int = intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, takeFlags)
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

    companion object {
        private const val READ_REQUEST_CODE: Int = 42
        const val WRITE_PERMISSION_REQUEST_CODE: Int = 43
        const val ICON_REQUEST_CODE: Int = 44
    }
}