package me.rosuh.easywatermark.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.SimpleSeekBarListener


class StyleFragment : BaseFragment() {

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = layoutInflater.inflate(R.layout.fragment_style, null)
        val tvDegree = root.findViewById<TextView>(R.id.tv_tips_degree).apply {
            text = "${shareViewModel.config.value?.degree ?: 0}°"
        }
        root.findViewById<SeekBar>(R.id.sb_degree).apply {
            progress = (shareViewModel.config.value?.degree ?: 0f).toInt()
            setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    super.onProgressChanged(seekBar, progress, fromUser)
                    shareViewModel.updateDegree(progress.toFloat())
                    tvDegree.text = "${shareViewModel.config.value?.degree ?: 0}°"
                }
            })
        }
        root.findViewById<ImageView>(R.id.iv_color_preview).apply {
            changeIvColor(this, shareViewModel.config.value?.textColor ?: Color.RED)
            setOnClickListener {
                if (activity == null) {
                    return@setOnClickListener
                }
                ColorPickerDialog.Builder(
                    activity,
                    R.style.ThemeOverlay_MaterialComponents_Dialog_MyDialog
                )
                    .setTitle(requireActivity().getString(R.string.tips_choose_color_dialog))
                    .setPreferenceName(SP_COLOR_PICKER_DIALOG)
                    .setPositiveButton("确认", object : ColorEnvelopeListener {
                        override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                            changeIvColor(this@apply, envelope?.color ?: Color.RED)
                        }
                    })
                    .setNegativeButton("取消") { dialogInterface, _ -> dialogInterface.dismiss() }
                    .attachAlphaSlideBar(true)
                    .attachBrightnessSlideBar(true)
                    .setBottomSpace(20)
                    .show()
            }
        }
        return root
    }

    private fun changeIvColor(iv: ImageView, color: Int) {
        (iv.drawable as GradientDrawable).setColor(color)
        with(shareViewModel) {
            updateTextColor(color)
            updateAlpha(Color.alpha(color))
        }
    }

    companion object {

        const val SP_COLOR_PICKER_DIALOG = "water_mark_color_picker_dialog"

        fun newInstance(): StyleFragment {
            return StyleFragment()
        }
    }

}