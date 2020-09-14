package me.rosuh.easywatermark.ui.panel

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.adapter.ColorPreviewAdapter
import me.rosuh.easywatermark.base.BaseFragment
import me.rosuh.easywatermark.utils.DetectedPerformanceSeekBarListener
import me.rosuh.easywatermark.utils.onItemClick
import me.rosuh.easywatermark.widget.ControllableScrollView


class StyleFragment : BaseFragment() {

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

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = layoutInflater.inflate(R.layout.fragment_style, container, false)
        val sv = root.findViewById<ControllableScrollView>(R.id.scrollView)

        val tvDegree = root.findViewById<TextView>(R.id.tv_tips_degree).apply {
            text = "${shareViewModel.config.value?.degree ?: 0}°"
        }
        root.findViewById<SeekBar>(R.id.sb_degree).apply {
            progress = (shareViewModel.config.value?.degree ?: 0f).toInt()
            setOnSeekBarChangeListener(object :
                DetectedPerformanceSeekBarListener(
                    config
                ) {
            }.apply {
                inTimeAction = { _: SeekBar?, _: Int, _: Boolean ->
                    tvDegree.text = "${shareViewModel.config.value?.degree ?: 0}°"
                    sv.canScroll = false
                }
                postAction = { _: SeekBar?, _: Int, fromUser: Boolean ->
                    if (fromUser) {
                        shareViewModel.updateDegree(progress.toFloat())
                    }
                }
            })
        }


        val tvAlpha = root.findViewById<TextView>(R.id.tv_tips_alpha).apply {
            text = "${getAlphaValue()}%"
        }
        val sbAlpha = root.findViewById<SeekBar>(R.id.sb_alpha).apply {
            progress = getAlphaValue()
            setOnSeekBarChangeListener(object :
                DetectedPerformanceSeekBarListener(
                    config
                ) {
            }.apply {
                inTimeAction = { _: SeekBar?, p: Int, _: Boolean ->
                    tvAlpha.text = "${p}%"
                    sv.canScroll = false
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

        root.findViewById<RecyclerView>(R.id.rv_color).apply {
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
        return root
    }

    private fun getAlphaValue(): Int {
        return ((shareViewModel.config.value?.alpha ?: 255).toFloat() / 255 * 100).toInt()
            .coerceAtLeast(0).coerceAtMost(100)
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

        const val SP_COLOR_PICKER_DIALOG = "water_mark_color_picker_dialog"

        fun newInstance(): StyleFragment {
            return StyleFragment()
        }
    }

}