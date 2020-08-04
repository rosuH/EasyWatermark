package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseFragment
import me.rosuh.easywatermark.utils.DetectedPerformanceSeekBarListener

class LayoutFragment : BaseFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = layoutInflater.inflate(R.layout.fragment_layout, null)
        val tvProgressHorizon = root.findViewById<TextView>(
            R.id.tv_progress_horizon
        )
        val tvProgressVertical = root.findViewById<TextView>(
            R.id.tv_progress_vertical
        )
        root.findViewById<SeekBar>(R.id.sb_horizon).apply {
            with(shareViewModel.config.value?.horizonGapPercent ?: 30) {
                progress = this
                tvProgressHorizon.text = this.toString()
            }
            setOnSeekBarChangeListener(object : DetectedPerformanceSeekBarListener(
                config
            ) {
            }.apply {
                inTimeAction = { seekBar: SeekBar?, i: Int, b: Boolean ->
                    tvProgressHorizon.text = seekBar?.progress.toString()
                }
                postAction = { seekBar: SeekBar?, i: Int ->
                    shareViewModel.updateHorizon(seekBar?.progress ?: 0)
                }
            })
        }

        root.findViewById<SeekBar>(R.id.sb_vertical).apply {
            with(shareViewModel.config.value?.verticalGapPercent ?: 30) {
                progress = this
                tvProgressVertical.text = this.toString()
            }
            setOnSeekBarChangeListener(object : DetectedPerformanceSeekBarListener(
                config
            ) {
            }.apply {
                inTimeAction = { seekBar: SeekBar?, i: Int, b: Boolean ->
                    tvProgressVertical.text =
                        seekBar?.progress.toString()
                }
                postAction = { seekBar: SeekBar?, i: Int ->
                    shareViewModel.updateVertical(seekBar?.progress ?: 0)
                }
            })
        }

        return root
    }

    companion object {
        fun newInstance(): LayoutFragment {
            return LayoutFragment()
        }
    }
}