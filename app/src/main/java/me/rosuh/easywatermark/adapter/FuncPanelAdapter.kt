package me.rosuh.easywatermark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseViewHolder
import me.rosuh.easywatermark.model.FuncTitleModel

class FuncPanelAdapter(
    val dataSet: ArrayList<FuncTitleModel>
) : RecyclerView.Adapter<FuncPanelAdapter.FuncTitleHolder>() {

    var selectedPos = 0
        set(value) {
            notifyItemChanged(field, "Selected")
            field = value
            notifyItemChanged(value, "Selected")
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FuncTitleHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_func_panel, parent, false)
        return FuncTitleHolder(view)
    }

    override fun onBindViewHolder(
        holder: FuncTitleHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        super.onBindViewHolder(holder, position, payloads)
        if (payloads.isNullOrEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        processUI(holder, position)
    }

    override fun onBindViewHolder(holder: FuncTitleHolder, position: Int) {
        processUI(holder, position)
    }

    private fun processUI(holder: FuncTitleHolder, position: Int) {
        if (position < 0 || position >= dataSet.size) {
            return
        }
        with(dataSet[position]) {
            holder.tvTitle.text = title
            holder.ivIcon.setImageResource(iconRes)
            if (position == selectedPos) {
                holder.ivIcon.drawable.setTint(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        R.color.colorAccent
                    )
                )
                holder.tvTitle.setTextColor(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        R.color.colorAccent
                    )
                )
            } else {
                holder.ivIcon.drawable.setTintList(null)
                holder.tvTitle.setTextColor(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        R.color.text_color_main
                    )
                )
            }
        }
    }

    override fun getItemCount(): Int {
        return dataSet.count()
    }

    fun seNewData(contentFunList: List<FuncTitleModel>, toPos: Int = selectedPos) {
        selectedPos = toPos
        dataSet.clear()
        dataSet.addAll(contentFunList)
        notifyDataSetChanged()
    }

    class FuncTitleHolder(view: View) : BaseViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
    }
}
