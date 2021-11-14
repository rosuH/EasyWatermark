package me.rosuh.easywatermark.ui.about

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.databinding.ActivityAboutBinding
import me.rosuh.easywatermark.ui.dialog.ChangeLogDialogFragment
import me.rosuh.easywatermark.utils.ktx.inflate
import me.rosuh.easywatermark.utils.ktx.openLink

@AndroidEntryPoint
class AboutActivity : AppCompatActivity() {

    private val binding by inflate<ActivityAboutBinding>()

    private val viewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        changeStatusBarStyle()
    }

    private fun changeStatusBarStyle() {
//        val flag = (
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
//                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
//                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
//                )
//        window?.decorView?.systemUiVisibility = flag
//        window?.statusBarColor = Color.TRANSPARENT
//        window?.navigationBarColor = Color.TRANSPARENT
    }

    private fun initView() {
        with(binding) {
            tvVersion.setOnClickListener {
                openLink("https://github.com/rosuH/EasyWatermark/releases/")
            }
            tvVersionValue.text = BuildConfig.VERSION_NAME
            tvRating.setOnClickListener {
                kotlin.runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=me.rosuh.easywatermark")
                        )
                    )
                }
            }
            tvFeedBack.setOnClickListener {
                openLink("https://github.com/rosuH/EasyWatermark/issues/new")
            }
            tvChangeLog.setOnClickListener {
                ChangeLogDialogFragment.safetyShow(supportFragmentManager)
            }
            tvOpenSource.setOnClickListener {
                kotlin.runCatching {
                    startActivity(
                        Intent(
                            this@AboutActivity,
                            OpenSourceActivity::class.java
                        )
                    )
                }
            }
            tvPrivacyCn.setOnClickListener {
                val browserIntent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md")
                    )
                kotlin.runCatching { startActivity(browserIntent) }
            }
            tvPrivacyEng.setOnClickListener {
                val browserIntent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md")
                    )
                kotlin.runCatching { startActivity(browserIntent) }
            }
            civAvatar.setOnClickListener {
                openLink("https://github.com/rosuH")
            }
            civAvatarDesigner.setOnClickListener {
                openLink("https://tovi.fun/")
            }
            ivBack.setOnClickListener { finish() }

            switchDebug.setOnCheckedChangeListener { _, isChecked ->
                viewModel.toggleBounds(isChecked)
            }

            viewModel.waterMark.observe(this@AboutActivity) {
                switchDebug.isChecked = viewModel.waterMark.value?.enableBounds ?: false
            }
        }
    }

}
