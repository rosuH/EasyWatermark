package me.rosuh.easywatermark.ui.panel

import android.graphics.Shader
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.FragmentTileModeBinding
import me.rosuh.easywatermark.ui.base.BaseBindFragment
import me.rosuh.easywatermark.utils.ktx.commitWithAnimation

class TileModeFragment : BaseBindFragment<FragmentTileModeBinding>() {

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentTileModeBinding {
        val b = FragmentTileModeBinding.inflate(layoutInflater, container, false)

        val checkedId = when(shareViewModel.waterMark.value?.tileMode) {
            Shader.TileMode.DECAL.ordinal -> R.id.rb_tile_mode_decal
            else -> R.id.rb_tile_mode_repeat
        }
        b.rgTileMode.check(checkedId)
        b.rgTileMode.setOnCheckedChangeListener { _, id ->
            when(id) {
                R.id.rb_tile_mode_decal -> shareViewModel.updateTileMode(Shader.TileMode.DECAL)
                else -> shareViewModel.updateTileMode(Shader.TileMode.REPEAT)
            }
        }
        return b
    }

    companion object {
        const val TAG = "TileModeFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    TileModeFragment(),
                    TAG
                )
            }
        }
    }
}
