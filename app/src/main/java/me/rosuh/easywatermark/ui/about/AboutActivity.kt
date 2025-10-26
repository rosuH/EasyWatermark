package me.rosuh.easywatermark.ui.about

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.palette.graphics.Palette
import dagger.hilt.android.AndroidEntryPoint
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.databinding.ActivityAboutBinding
import me.rosuh.easywatermark.utils.ktx.bgColor
import me.rosuh.easywatermark.utils.ktx.colorBackground
import me.rosuh.easywatermark.utils.ktx.colorPrimary
import me.rosuh.easywatermark.utils.ktx.colorSecondaryContainer
import me.rosuh.easywatermark.utils.ktx.colorSurface
import me.rosuh.easywatermark.utils.ktx.doOnApplyWindowInsets
import me.rosuh.easywatermark.utils.ktx.inflate
import me.rosuh.easywatermark.utils.ktx.openLink
import me.rosuh.easywatermark.utils.ktx.titleTextColor
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.core.view.updatePadding

@AndroidEntryPoint
class AboutActivity : AppCompatActivity() {

    private val binding by inflate<ActivityAboutBinding>()

    private val viewModel: AboutViewModel by viewModels()

    private lateinit var bgDrawable: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        changeStatusBarStyle()
        window?.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.navigationBarDividerColor = Color.TRANSPARENT
        }
    }

    private fun changeStatusBarStyle(color: Int = colorSurface) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.findViewById<View>(android.R.id.content)?.foreground = null
    }


    private fun initView() {
        with(binding) {
            root.doOnApplyWindowInsets { view, insets, initialPadding ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    left = initialPadding.left + systemBars.left,
                    top = initialPadding.top + systemBars.top,
                    right = initialPadding.right + systemBars.right,
                    bottom = initialPadding.bottom
                )
            }
            nsv.doOnApplyWindowInsets { view, insets, initialPadding ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    left = initialPadding.left,
                    top = initialPadding.top,
                    right = initialPadding.right,
                    bottom = initialPadding.bottom + systemBars.bottom
                )
            }
            bgDrawable = ContextCompat.getDrawable(
                this@AboutActivity,
                me.rosuh.easywatermark.R.drawable.bg_gradient_about_page
            ) as GradientDrawable

            this.root.background = bgDrawable
            tvVersion.setOnClickListener {
                openLink("https://github.com/rosuH/EasyWatermark/releases/")
            }
            tvVersionValue.text = BuildConfig.VERSION_NAME
            tvRating.setOnClickListener {
                openLink(Uri.parse("market://details?id=me.rosuh.easywatermark"))
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
                openLink(Uri.parse("https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"))
            }
            tvPrivacyEng.setOnClickListener {
                openLink(Uri.parse("https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"))
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

            switchDynamicColor.isChecked = CMonet.isDynamicColorAvailable()

            switchDynamicColor.setOnCheckedChangeListener { _, isChecked ->
                viewModel.toggleSupportDynamicColor(isChecked)
                Toast.makeText(
                    this@AboutActivity,
                    "Reboot and you'll get what you want.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            binding.clDevContainer.backgroundTintList =
                ColorStateList.valueOf(this@AboutActivity.colorSecondaryContainer)
            binding.clDesignerContainer.backgroundTintList =
                ColorStateList.valueOf(this@AboutActivity.colorSecondaryContainer)

            viewModel.waterMark.observe(this@AboutActivity) {
                switchDebug.isChecked = viewModel.waterMark.value?.enableBounds ?: false
            }

            viewModel.palette.observe(this@AboutActivity) {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        applyPaletteForSupportNight(it)
                    }

                    it == null -> {
                        binding.clContainer.children
                            .plus(binding.tvTitle)
                            .plus(binding.tvSubTitle)
                            .plus(binding.tvTitleDesigner)
                            .plus(binding.tvSubTitleDesigner)
                            .forEach { view ->
                                if (view !is TextView) {
                                    return@forEach
                                }
                                view.setTextColor(Color.WHITE)
                                TextViewCompat.setCompoundDrawableTintList(
                                    view,
                                    ColorStateList.valueOf(Color.WHITE)
                                )
                            }
                        return@observe
                    }

                    else -> {
                        applyPaletteForSupportLightStatusIcon(it)
                    }
                }
            }
        }
    }

    private fun applyPaletteForSupportNight(palette: Palette?) {
        val bgColor = palette?.bgColor(this@AboutActivity) ?: this@AboutActivity.colorPrimary
        val bgAccent = palette?.bgColor(this@AboutActivity) ?: this@AboutActivity.colorBackground
        val colorList = arrayOf(
            ColorUtils.setAlphaComponent(bgColor, 255),
            ColorUtils.setAlphaComponent(bgAccent, 65),
        ).toIntArray()
        bgDrawable.colors = colorList
    }

    private fun applyPaletteForSupportLightStatusIcon(palette: Palette) {
        val bgColor = palette.bgColor(this@AboutActivity)
        val bgAccent = palette.bgColor(this@AboutActivity)
        val colorList = arrayOf(
            ColorUtils.setAlphaComponent(bgColor, 255),
            ColorUtils.setAlphaComponent(bgAccent, 65),
        ).toIntArray()
        bgDrawable.colors = colorList

        val textColor = palette.titleTextColor(this@AboutActivity)
        binding.clContainer.children
            .plus(binding.tvTitle)
            .plus(binding.tvSubTitle)
            .plus(binding.tvTitleDesigner)
            .plus(binding.tvSubTitleDesigner)
            .forEach { view ->
                if (view !is TextView) {
                    return@forEach
                }
                view.setTextColor(textColor)
                TextViewCompat.setCompoundDrawableTintList(
                    view,
                    ColorStateList.valueOf(textColor)
                )
            }
    }

    private fun applyPaletteForNoMatterWhoYouAre(palette: Palette) {
        val bgColor = palette.bgColor(this@AboutActivity)
        val bgAccent = palette.bgColor(this@AboutActivity)
        val colorList = arrayOf(
            ColorUtils.setAlphaComponent(bgColor, 255),
            ColorUtils.setAlphaComponent(bgAccent, 65),
        ).toIntArray()
        bgDrawable.colors = colorList

        val textColor = palette.titleTextColor(this@AboutActivity)
        binding.clContainer.children
            .plus(binding.tvTitle)
            .plus(binding.tvSubTitle)
            .plus(binding.tvTitleDesigner)
            .plus(binding.tvSubTitleDesigner)
            .forEach { view ->
                if (view !is TextView) {
                    return@forEach
                }
                view.setTextColor(textColor)
                TextViewCompat.setCompoundDrawableTintList(
                    view,
                    ColorStateList.valueOf(textColor)
                )
            }
    }
}
