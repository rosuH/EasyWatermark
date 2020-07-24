package me.rosuh.easywatermark.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R

class ControlPanelAdapter(
    private val titleList: Array<String>,
    private val iconList: Array<Int>
) : RecyclerView.Adapter<ControlPanelAdapter.TitleViewHolder>() {

    class TitleViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private var selectedPos: Int = 0

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): TitleViewHolder {
        val textView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tool_bar, parent, false) as TextView
        return TitleViewHolder(
            textView
        )
    }

    override fun onBindViewHolder(holder: TitleViewHolder, position: Int) {
        holder.textView.apply {
            setBackgroundColor(
                if (position == selectedPos) ContextCompat.getColor(
                    context,
                    R.color.colorAccent
                ) else ContextCompat.getColor(
                    context,
                    R.color.colorPrimaryDark
                )
            )
            text = titleList[position]
            setCompoundDrawablesWithIntrinsicBounds(0, iconList[position], 0, 0)
        }
    }

    override fun getItemCount() = titleList.size

    fun updateSelected(position: Int) {
        selectedPos = position
        notifyDataSetChanged()
    }
}
