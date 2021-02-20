package me.rosuh.easywatermark.ui.panel

import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseBindFragment
import me.rosuh.easywatermark.databinding.FragmentContentBinding
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.ui.MainActivity
import me.rosuh.easywatermark.ui.MainActivity.Companion.REQ_PICK_ICON
import me.rosuh.easywatermark.ui.dialog.EditTextBSDialogFragment
import me.rosuh.easywatermark.utils.DetectedPerformanceSeekBarListener

class ContentFragment : BaseBindFragment<FragmentContentBinding>() {

    private var tvTitleTextStyle: View? = null
    private var btnImage: MaterialButton? = null
    private var btnText: MaterialButton? = null
    private var ivTextStyle: ImageView? = null

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentContentBinding {
        return FragmentContentBinding.inflate(layoutInflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvProgressVertical.apply {
            text = shareViewModel.config.value?.textSize.toString()
        }
        with(binding.root) {
            ivTextStyle = findViewById<ImageView>(R.id.iv_text_style).apply {
                setImageResource(if (shareViewModel.config.value?.textStyle == Paint.Style.FILL) R.drawable.ic_text_style_fill else R.drawable.ic_text_style_stroke)
                setOnClickListener {
                    val isFill = shareViewModel.config.value?.textStyle == Paint.Style.FILL
                    if (isFill) {
                        shareViewModel.updateTextStyle(Paint.Style.STROKE)
                    } else {
                        shareViewModel.updateTextStyle(Paint.Style.FILL)
                    }
                    setImageResource(if (shareViewModel.config.value?.textStyle == Paint.Style.FILL) R.drawable.ic_text_style_fill else R.drawable.ic_text_style_stroke)
                }
            }
            btnText = findViewById<MaterialButton>(R.id.btn_text).apply {
                setOnClickListener {
                    EditTextBSDialogFragment.safetyShow(childFragmentManager)
                }
            }

            btnImage = findViewById<MaterialButton>(R.id.btn_img).apply {
                setOnClickListener {
                    (requireActivity() as? MainActivity)?.performFileSearch(REQ_PICK_ICON)
                }
            }
            tvTitleTextStyle = findViewById(R.id.tv_title_style)
        }

        binding.sbContentSize.apply {
            with(shareViewModel.config.value?.textSize ?: 14f) {
                progress = this.toInt()
            }
            setOnSeekBarChangeListener(object : DetectedPerformanceSeekBarListener(config) {

            }.apply {
                inTimeAction = { _: SeekBar?, _: Int, _: Boolean ->
                    binding.tvProgressVertical.text = progress.toString()
                    binding.scrollView.canScroll = false
                }
                postAction = { _: SeekBar?, _: Int, fromUser: Boolean ->
                    if (fromUser) {
                        shareViewModel.updateTextSize(progress.toFloat())
                    }
                }
            })
        }

        initObserver()
    }

    private fun initObserver(
        sbTextSize: SeekBar = binding.sbContentSize,
        tvTextSize: TextView = binding.tvProgressVertical
    ) {
        shareViewModel.config.observe(viewLifecycleOwner, Observer<WaterMarkConfig> {
            with(it.textSize) {
                sbTextSize.progress = this.coerceAtLeast(0f).toInt()
                tvTextSize.text = this.coerceAtLeast(0f).toInt().toString()
            }
            btnText?.apply {
                strokeColor =
                    if (it.markMode == WaterMarkConfig.MarkMode.Text) {
                        ColorStateList.valueOf(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.white
                            )
                        )
                    } else {
                        ColorStateList.valueOf(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.colorAccent_30
                            )
                        )
                    }
            }
            btnImage?.apply {
                strokeColor =
                    if (it.markMode == WaterMarkConfig.MarkMode.Image) {
                        ColorStateList.valueOf(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.white
                            )
                        )
                    } else {
                        ColorStateList.valueOf(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.colorAccent_30
                            )
                        )
                    }
            }
            tvTitleTextStyle?.isVisible = it.markMode == WaterMarkConfig.MarkMode.Text
            ivTextStyle?.isVisible = it.markMode == WaterMarkConfig.MarkMode.Text
        })
    }

    companion object {
        fun newInstance(): ContentFragment {
            return ContentFragment()
        }
    }
}