package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseFragment
import me.rosuh.easywatermark.utils.SimpleSeekBarListener

class LayoutFragment : BaseFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = layoutInflater.inflate(R.layout.fragment_layout, null)

        root.findViewById<SeekBar>(R.id.sb_horizon).apply {
            with(shareViewModel.config.value?.horizonGapPercent ?: 30) {
                progress = this
                root.findViewById<TextView>(R.id.tv_progress_horizon).text = this.toString()
            }
            setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    shareViewModel.updateHorizon(progress)
                    root.findViewById<TextView>(R.id.tv_progress_horizon).text = progress.toString()
                }
            })
        }

        root.findViewById<SeekBar>(R.id.sb_vertical).apply {
            with(shareViewModel.config.value?.verticalGapPercent ?: 30) {
                progress = this
                root.findViewById<TextView>(R.id.tv_progress_vertical).text = this.toString()
            }
            setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    shareViewModel.updateVertical(progress)
                    root.findViewById<TextView>(R.id.tv_progress_vertical).text =
                        progress.toString()
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