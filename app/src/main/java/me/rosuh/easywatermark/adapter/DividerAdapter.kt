package me.rosuh.easywatermark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseVideHolder

class DividerAdapter(
    private val dividerSize: Int = 1
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_divider, parent, false)

        return DividerHolder(
            root
        )
    }

    override fun getItemCount(): Int {
        return dividerSize
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

    internal class DividerHolder(val root: View) : BaseVideHolder(root)
}
