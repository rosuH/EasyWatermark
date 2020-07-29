package me.rosuh.easywatermark.ui.panel

import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseFragment
import me.rosuh.easywatermark.utils.SimpleSeekBarListener

class TextFragment : BaseFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = layoutInflater.inflate(R.layout.fragment_text, null)
        val tvTextSize = root.findViewById<TextView>(R.id.tv_progress_vertical).apply {
            text = shareViewModel.config.value?.textSize.toString()
        }
        root.findViewById<TextView>(R.id.tv_text_style).apply {
            setCompoundDrawablesWithIntrinsicBounds(
                0,
                if (shareViewModel.config.value?.textStyle == Paint.Style.FILL) R.drawable.ic_text_style_fill else R.drawable.ic_text_style_stroke,
                0,
                0
            )
            setOnClickListener {
                val isFill = shareViewModel.config.value?.textStyle == Paint.Style.FILL
                if (isFill) {
                    shareViewModel.updateTextStyle(Paint.Style.STROKE)
                } else {
                    shareViewModel.updateTextStyle(Paint.Style.FILL)
                }
                setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    if (shareViewModel.config.value?.textStyle == Paint.Style.FILL) R.drawable.ic_text_style_fill else R.drawable.ic_text_style_stroke,
                    0,
                    0
                )
            }
        }
        root.findViewById<TextInputEditText>(R.id.et_water_text).apply {
            setText(shareViewModel.config.value?.text.toString())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    shareViewModel.updateText(s?.toString() ?: "")
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        root.findViewById<SeekBar>(R.id.sb_text_size).apply {
            with(shareViewModel.config.value?.textSize ?: 14f) {
                progress = this.toInt()
            }
            setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    shareViewModel.updateTextSize(progress.toFloat())
                    tvTextSize.text = progress.toString()
                }
            })
        }
        return root
    }

    companion object {
        fun newInstance(): TextFragment {
            return TextFragment()
        }
    }
}