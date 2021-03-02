package me.rosuh.easywatermark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.FuncTitleModel

class FuncPanelAdapter(
    val dataSet: ArrayList<FuncTitleModel>
) : RecyclerView.Adapter<FuncPanelAdapter.FuncTitleHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FuncTitleHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_func_panel, parent, false)
        return FuncTitleHolder(view)
    }

    override fun onBindViewHolder(holder: FuncTitleHolder, position: Int) {
        if (position < 0 || position >= dataSet.size) {
            return
        }
        with(dataSet[position]) {
            holder.tvTitle.text = title
            holder.ivIcon.setImageResource(iconRes)
        }
    }

    override fun getItemCount(): Int {
        return dataSet.count()
    }


    fun seNewData(contentFunList: List<FuncTitleModel>) {
        dataSet.clear()
        dataSet.addAll(contentFunList)
        notifyDataSetChanged()
    }

    class FuncTitleHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
    }
}