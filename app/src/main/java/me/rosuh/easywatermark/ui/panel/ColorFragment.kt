package me.rosuh.easywatermark.ui.panel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.adapter.ColorPreviewAdapter
import me.rosuh.easywatermark.base.BaseBindFragment
import me.rosuh.easywatermark.databinding.FragmentColorBinding
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.utils.DetectedPerformanceSeekBarListener
import me.rosuh.easywatermark.utils.onItemClick

class ColorFragment : BaseBindFragment<FragmentColorBinding>() {

    private val white by lazy {
        Color.WHITE
    }

    private val black by lazy {
        Color.BLACK
    }

    private val yellow by lazy {
        Color.parseColor("#FFB800")
    }

    private val orange by lazy {
        Color.parseColor("#FF3535")
    }

    private val pink by lazy {
        Color.parseColor("#FF008A")
    }

    private val blue by lazy {
        Color.parseColor("#00D1FF")
    }

    private val green by lazy {
        Color.parseColor("#1BFF3F")
    }

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentColorBinding {
        val b = FragmentColorBinding.inflate(layoutInflater, container, false)

        val tvAlpha = b.tvTipsAlpha.apply {
            text = "${getAlphaValue()}%"
        }
        val sbAlpha = b.sbAlpha.apply {
            progress = getAlphaValue()
            setOnSeekBarChangeListener(object :
                DetectedPerformanceSeekBarListener(
                    config
                ) {
            }.apply {
                inTimeAction = { _: SeekBar?, p: Int, _: Boolean ->
                    tvAlpha.text = "${p}%"
                }
                postAction = { _: SeekBar?, p: Int, fromUser: Boolean ->
                    if (fromUser) {
                        shareViewModel.updateAlpha((p.toFloat() / 100 * 255).toInt())
                    }
                }
            })
        }

        val savedColor = shareViewModel.config.value?.textColor ?: Color.WHITE
        val colorArrayList = buildColorList(savedColor)

        b.rvColor.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter =
                ColorPreviewAdapter(colorArrayList)
            onItemClick { recyclerView, position, _ ->
                if (position == colorArrayList.size - 1 && activity != null) {
                    showColorPickerDialog(this.adapter as ColorPreviewAdapter)
                    return@onItemClick
                }
                (recyclerView.adapter as ColorPreviewAdapter).let {
                    val color = it.previewList[position].color
                    changeIvColor(color)
                    it.updateSelectedColor(color)
                }
            }
        }
        initObserver(sbAlpha, tvAlpha)

        return b
    }

    private fun initObserver(
        sbAlpha: SeekBar,
        tvAlpha: TextView
    ) {
        shareViewModel.config.observe(viewLifecycleOwner, {
            with(it.alpha) {
                val intAlpha =
                    (this.toFloat() / 255 * 100).toInt().coerceAtLeast(0).coerceAtMost(100)
                sbAlpha.progress = intAlpha
                tvAlpha.text = "${intAlpha}%"
            }
        })
    }

    private fun getAlphaValue(): Int {
        return ((shareViewModel.config.value?.alpha ?: 255).toFloat() / 255 * 100).toInt()
            .coerceAtLeast(0).coerceAtMost(100)
    }

    private fun showColorPickerDialog(adapter: ColorPreviewAdapter) {
        ColorPickerDialog.Builder(
            activity,
            R.style.MaterialAlertDialog
        )
            .setTitle(requireActivity().getString(R.string.tips_choose_color_dialog))
            .setPreferenceName(SP_COLOR_PICKER_DIALOG)
            .setPositiveButton(
                requireActivity().getString(R.string.tips_confirm_dialog),
                object : ColorEnvelopeListener {
                    override fun onColorSelected(
                        envelope: ColorEnvelope?,
                        fromUser: Boolean
                    ) {
                        envelope?.color?.let {
                            adapter.updateSelectedColor(it)
                            changeIvColor(it, Color.alpha(it))
                        }
                    }
                })
            .setNegativeButton(requireActivity().getString(R.string.tips_cancel_dialog)) { dialogInterface, _ -> dialogInterface.dismiss() }
            .attachAlphaSlideBar(true)
            .attachBrightnessSlideBar(true)
            .setBottomSpace(20)
            .show()
    }

    private fun changeIvColor(color: Int, alpha: Int = -1) {
        with(shareViewModel) {
            updateTextColor(color)
            if (alpha != -1) {
                updateAlpha(alpha)
            }
        }
    }

    private fun buildColorList(savedColor: Int): ArrayList<ColorPreviewAdapter.PreViewModel> {
        return arrayListOf(
            ColorPreviewAdapter.PreViewModel(
                color = white,
                selected = savedColor == white
            ),
            ColorPreviewAdapter.PreViewModel(color = black, selected = savedColor == black),
            ColorPreviewAdapter.PreViewModel(
                color = yellow,
                selected = savedColor == yellow
            ),
            ColorPreviewAdapter.PreViewModel(color = orange, selected = savedColor == orange),
            ColorPreviewAdapter.PreViewModel(color = pink, selected = savedColor == pink),
            ColorPreviewAdapter.PreViewModel(color = blue, selected = savedColor == blue),
            ColorPreviewAdapter.PreViewModel(color = green, selected = savedColor == green),
            ColorPreviewAdapter.PreViewModel(
                type = ColorPreviewAdapter.PreviewType.Res,
                resId = R.drawable.ic_btn_color_picker
            )
        )
    }

    companion object {
        const val TAG = "ColorFragment"
        private const val SP_COLOR_PICKER_DIALOG = "water_mark_color_picker_dialog"


        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    ColorFragment(),
                    TAG
                )
            }
        }
    }
}