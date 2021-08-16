package me.rosuh.easywatermark.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseViewHolder
import me.rosuh.easywatermark.model.TextPaintStyle
import me.rosuh.easywatermark.model.TextTypeface

class TextTypefaceAdapter(
    private val dataList: ArrayList<TextTypefaceModel>,
    initTypeface: TextTypeface? = TextTypeface.Normal,
    initTextStyle: TextPaintStyle? = TextPaintStyle.Fill,
    private val onClickAction: (pos: Int, typeface: TextTypeface) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var selectedPos: Int

    private var textPaintStyle: TextPaintStyle

    init {
        selectedPos = dataList.indexOfFirst { it.textTypeface == initTypeface }.coerceAtLeast(0)
        textPaintStyle = initTextStyle ?: TextPaintStyle.Fill
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
                model.textTypeface.applyStyle(this)
            }
            tvTitle?.text = model.title
            tvPreview.setTextColor(
                if (selected) ContextCompat.getColor(
                    holder.root.context,
                    R.color.colorAccent
                ) else ContextCompat.getColor(holder.root.context, R.color.text_color_main)
            )
            textPaintStyle.applyStyle(tvPreview)
            tvPreview.setOnClickListener {
                onClickAction.invoke(position, model.textTypeface)
                updateSelected(position)
            }
        }
    }

    fun updateTextStyle(textPaintStyle: TextPaintStyle) {
        this.textPaintStyle = textPaintStyle
        notifyDataSetChanged()
    }


    private fun updateSelected(pos: Int) {
        if (pos == selectedPos) {
            return
        }
        notifyItemChanged(selectedPos, "Selected")
        selectedPos = pos
        notifyItemChanged(selectedPos, "Selected")
    }

    internal class TypefaceHolder(val root: View) : BaseViewHolder(root) {
        val tvPreview: TextView by lazy { root.findViewById(R.id.tv_preview) }
        val tvTitle: TextView? by lazy { root.findViewById(R.id.tv_title) }
    }


    data class TextTypefaceModel(
        val textTypeface: TextTypeface = TextTypeface.Normal,
        val title: String
    )

    companion object {
        fun obtainDefaultTypefaceList(context: Context): ArrayList<TextTypefaceModel> {
            return arrayListOf(
                TextTypefaceModel(
                    TextTypeface.Normal,
                    context.getString(R.string.text_typeface_normal)
                ),
                TextTypefaceModel(
                    TextTypeface.Bold,
                    context.getString(R.string.text_typeface_bold)
                ),
                TextTypefaceModel(
                    TextTypeface.Italic,
                    context.getString(R.string.text_typeface_italic)
                ),
                TextTypefaceModel(
                    TextTypeface.BoldItalic,
                    context.getString(R.string.text_typeface_bold_italic)
                ),
            )
        }
    }
}
