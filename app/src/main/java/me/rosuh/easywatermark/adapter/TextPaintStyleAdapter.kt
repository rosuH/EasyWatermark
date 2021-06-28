package me.rosuh.easywatermark.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseVideHolder
import me.rosuh.easywatermark.model.TextPaintStyle

class TextPaintStyleAdapter(
    private val dataList: ArrayList<TextPaintStyleModel>,
    initPaintStyle: TextPaintStyle? = TextPaintStyle.Fill,
    private val onClickAction: (pos: Int, paintStyle: TextPaintStyle) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var selectedPos: Int

    init {
        selectedPos = dataList.indexOfFirst { it.paintStyle == initPaintStyle }.coerceAtLeast(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_typeface_style, parent, false)

        return TypefaceHolder(
            root
        )
    }

    override fun getItemCount(): Int {
        return dataList.size
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        super.onBindViewHolder(holder, position, payloads)
        if (payloads.isNullOrEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        handleView(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        handleView(holder, position)
    }

    private fun handleView(holder: RecyclerView.ViewHolder, position: Int) {
        val model = dataList[position]
        val selected = position == selectedPos
        with(holder as TypefaceHolder) {
            tvPreview.apply {
                model.paintStyle.applyStyle(this)
            }
            tvTitle?.text = model.title
            tvPreview.setTextColor(
                if (selected) ContextCompat.getColor(
                    holder.root.context,
                    R.color.colorAccent
                ) else ContextCompat.getColor(holder.root.context, R.color.text_color_main)
            )
            tvPreview.setOnClickListener {
                onClickAction.invoke(position, model.paintStyle)
                updateSelected(position)
            }
        }
    }

    private fun updateSelected(pos: Int) {
        if (pos == selectedPos) {
            return
        }
        notifyItemChanged(selectedPos, "Selected")
        selectedPos = pos
        notifyItemChanged(selectedPos, "Selected")
    }

    internal class TypefaceHolder(val root: View) : BaseVideHolder(root) {
        val tvPreview: TextView by lazy { root.findViewById(R.id.tv_preview) }
        val tvTitle: TextView? by lazy { root.findViewById(R.id.tv_title) }
    }

    data class TextPaintStyleModel(
        val paintStyle: TextPaintStyle = TextPaintStyle.Fill,
        val title: String
    )

    companion object {
        fun obtainDefaultPaintStyleList(context: Context): ArrayList<TextPaintStyleModel> {
            return arrayListOf(
                TextPaintStyleModel(
                    TextPaintStyle.Fill,
                    context.getString(R.string.text_paint_fill)
                ),
                TextPaintStyleModel(
                    TextPaintStyle.Stroke,
                    context.getString(R.string.text_paint_stroke)
                ),
            )
        }
    }
}
