package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.adapter.DividerAdapter
import me.rosuh.easywatermark.adapter.TextPaintStyleAdapter
import me.rosuh.easywatermark.adapter.TextTypefaceAdapter
import me.rosuh.easywatermark.base.BaseBindFragment
import me.rosuh.easywatermark.databinding.FragmentTextStyleBinding
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.widget.utils.SimpleOvserScrollEdgeEffect

class TextStyleFragment : BaseBindFragment<FragmentTextStyleBinding>() {
    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentTextStyleBinding {
        return FragmentTextStyleBinding.inflate(layoutInflater)
    }

    private val paintStyleAdapter by lazy {
        TextPaintStyleAdapter(
            TextPaintStyleAdapter.obtainDefaultPaintStyleList(
                requireContext()
            ),
            shareViewModel.config.value?.textStyle
        ) { _, paintStyle ->
            shareViewModel.updateTextStyle(paintStyle)
            typefaceAdapter.updateTextStyle(paintStyle)
        }
    }
    private val typefaceAdapter by lazy {
        TextTypefaceAdapter(
            TextTypefaceAdapter.obtainDefaultTypefaceList(
                requireContext()
            ),
            shareViewModel.config.value?.textTypeface
        ) { _, typeface ->
            shareViewModel.updateTextTypeface(typeface)
        }
    }

    private val concatAdapter by lazy {
        ConcatAdapter(
            paintStyleAdapter,
            DividerAdapter(),
            typefaceAdapter,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvColor.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = concatAdapter
            edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
                override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                    return SimpleOvserScrollEdgeEffect(this@apply, direction, context)
                }
            }
        }
    }

    companion object {
        const val TAG = "TextStyleFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    TextStyleFragment(),
                    TAG
                )
            }
        }
    }
}