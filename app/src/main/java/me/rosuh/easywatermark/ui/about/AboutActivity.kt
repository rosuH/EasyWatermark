package me.rosuh.easywatermark.ui.about

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.ActivityAboutBinding
import me.rosuh.easywatermark.ktx.inflate
import me.rosuh.easywatermark.ktx.openLink
import me.rosuh.easywatermark.model.UserConfig


class AboutActivity : AppCompatActivity() {

    private val binding by inflate<ActivityAboutBinding>()

    private val viewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        initObserver()
        changeStatusBarStyle()
    }

    private fun initObserver() {
        viewModel.userConfig.observe(this, {
            if (it == null) {
                return@observe
            }
            binding.tvOutputValue.text = trapFormattingValue(it)
        })
    }

    private fun changeStatusBarStyle() {
        val flag = (View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        window?.decorView?.systemUiVisibility = flag
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor = Color.TRANSPARENT
    }

    private fun initView() {
        with(binding) {
            tvOutputValue.let {
                it.text = "${trapFormattingValue(viewModel.userConfig.value)}%"
                it.setOnClickListener {
                    showOutputDialog()
                }
            }
            tvOutput.setOnClickListener {
                showOutputDialog()
            }
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
                openLink("https://github.com/rosuH/EasyWatermark/releases/")
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
        }
    }

    private fun showOutputDialog() {
        val curFormat = viewModel.userConfig.value?.outputFormat ?: Bitmap.CompressFormat.JPEG
        val curLevel = viewModel.userConfig.value?.compressLevel ?: 95

        MaterialAlertDialogBuilder(this@AboutActivity, R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(getString(R.string.dialog_out_put_title))
            .setSingleChoiceItems(
                arrayOf("JPG 100%", "JPG 95%", "JPG 80%", "PNG"),
                getSelectedFormatting(viewModel.userConfig.value)
            ) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.saveOutput(Bitmap.CompressFormat.JPEG, 100)
                    }
                    1 -> {
                        viewModel.saveOutput(Bitmap.CompressFormat.JPEG, 95)
                    }
                    2 -> {
                        viewModel.saveOutput(Bitmap.CompressFormat.JPEG, 80)
                    }
                    3 -> {
                        viewModel.saveOutput(Bitmap.CompressFormat.PNG, 95)
                    }
                }
            }
            .setPositiveButton(R.string.tips_confirm_dialog) { dialog, _ ->
                dialog.cancel()
            }
            .setNegativeButton(R.string.tips_cancel_dialog) { dialog, _ ->
                viewModel.saveOutput(curFormat, curLevel)
                dialog.cancel()
            }
            .setCancelable(true)
            .show()
    }

    private fun getSelectedFormatting(config: UserConfig?): Int {
        if (config == null) {
            return 1
        }
        return when {
            config.outputFormat == Bitmap.CompressFormat.PNG -> 3
            config.compressLevel == 100 -> 0
            config.compressLevel == 80 -> 2
            else -> 1
        }
    }

    private fun trapFormattingValue(config: UserConfig?): String {
        if (config == null) {
            return ""
        }
        val format = when (config.outputFormat) {
            Bitmap.CompressFormat.JPEG -> "JPG"
            Bitmap.CompressFormat.PNG -> "PNG"
            Bitmap.CompressFormat.WEBP -> "WEBP"
        }
        val value = if (config.outputFormat == Bitmap.CompressFormat.JPEG) {
            " ${config.compressLevel}%"
        } else {
            ""
        }
        return "$format$value"
    }
}