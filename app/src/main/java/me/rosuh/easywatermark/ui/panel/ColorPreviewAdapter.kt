package me.rosuh.easywatermark.ui.panel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.widget.SelectableImageView

class ColorPreviewAdapter(
    val previewList: ArrayList<PreViewModel>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        previewList.find {
            it.selected
        } ?: run {
            previewList.last().selected = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_preview, null)

        return PreviewHolder(root)
    }

    override fun getItemCount(): Int {
        return previewList.size
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val model = previewList[position]
        (holder as PreviewHolder).siv.apply {
            isSelected = model.selected
            circleColor = model.color
            circleResId = model.resId
        }
    }

    fun updateSelectedColor(color: Int) {
        var hasToggled = false
        previewList.forEachIndexed { index, preViewModel ->
            preViewModel.selected = preViewModel.color == color && !hasToggled
            if (preViewModel.selected) {
                hasToggled = true
            }
            if (index == previewList.size - 1 && !hasToggled) {
                previewList.last().selected = true
            }
        }
        notifyDataSetChanged()
    }

    internal class PreviewHolder(private val root: View) : RecyclerView.ViewHolder(root) {
        val siv: SelectableImageView = root.findViewById(R.id.siv_color)
    }

    sealed class PreviewType {
        object Color : PreviewType()
        object Res : PreviewType()
    }

    data class PreViewModel(
        val type: PreviewType = PreviewType.Color,
        val color: Int = Color.WHITE,
        val resId: Int = -1,
        var selected: Boolean = false
    )
}
