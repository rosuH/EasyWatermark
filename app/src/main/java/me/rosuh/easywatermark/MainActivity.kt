package me.rosuh.easywatermark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.android.synthetic.main.activity_main.*
import me.rosuh.easywatermark.adapter.ControlPanelAdapter
import me.rosuh.easywatermark.config.WaterMarkConfig
import me.rosuh.easywatermark.ui.LayoutFragment
import me.rosuh.easywatermark.ui.StyleFragment
import me.rosuh.easywatermark.ui.TextFragment
import me.rosuh.easywatermark.utils.ZoomOutPageTransformer
import me.rosuh.easywatermark.utils.onItemClick


class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        initView()
        initObserver()
    }

    private fun initObserver() {
        viewModel.config.observe(this, Observer<WaterMarkConfig> {
            iv_photo?.config = it
            btn_add.isVisible = it.uri.toString().isEmpty()
        })

        viewModel.saveState.observe(this, Observer { state ->
            when (state) {
                MainViewModel.State.Saving -> {
                    Toast.makeText(this, getString(R.string.tips_saving), Toast.LENGTH_SHORT).show()
                }
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
                        getString(R.string.tips_error) + state.msg,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun initView() {
        btn_add.apply {
            setOnClickListener { performFileSearch() }
        }

        val titleArray = arrayOf(
            getString(R.string.title_layout),
            getString(R.string.title_style),
            getString(R.string.title_text)
        )

        val iconArray = arrayOf(
            R.drawable.ic_layout_title,
            R.drawable.ic_style_title,
            R.drawable.ic_text_title
        )

        val fragmentArray = arrayOf(
            initFragments(vp_control_panel, 0, LayoutFragment.newInstance()),
            initFragments(vp_control_panel, 1, StyleFragment.newInstance()),
            initFragments(vp_control_panel, 2, TextFragment.newInstance())
        )

        rv_tool_bar.apply {
            adapter = ControlPanelAdapter(titleArray, iconArray)
            layoutManager =
                LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            onItemClick { _, position, v ->
                (adapter as ControlPanelAdapter).updateSelected(position)
                vp_control_panel.currentItem = position
            }
        }

        val pagerAdapter = ControlPanelPagerAdapter(this, fragmentArray)
        vp_control_panel.apply {
            adapter = pagerAdapter
            setPageTransformer(ZoomOutPageTransformer())
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    (rv_tool_bar.adapter as ControlPanelAdapter).updateSelected(position)
                }
            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            true
        }

        R.id.action_pick -> {
            performFileSearch()
            true
        }

        R.id.action_save -> {
            viewModel.saveImage(contentResolver)
            true
        }

        R.id.action_share -> {
            viewModel.shareImage(this)
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, READ_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                viewModel.updateUri(uri)
            }
        } else {
            Toast.makeText(this, getString(R.string.tips_do_not_choose_image), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private inner class ControlPanelPagerAdapter(
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
    }
}