package me.rosuh.easywatermark.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.databinding.ItemTemplateListBinding

class TextContentTemplateListAdapter(
    private val listener: TextContentTemplateListAdapterListener? = null
) : RecyclerView.Adapter<TextContentTemplateListAdapter.ViewHolder>() {

    private val diffCallback = object : DiffUtil.ItemCallback<Template>() {
        override fun areItemsTheSame(oldItem: Template, newItem: Template): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Template, newItem: Template): Boolean {
            return oldItem == newItem
        }
    }

    private val mDiffer: AsyncListDiffer<Template> = AsyncListDiffer(this, diffCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemTemplateListBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun getItemCount(): Int {
        return mDiffer.currentList.count()
    }

    fun submitList(list: List<Template>) {
        mDiffer.submitList(list)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mDiffer.currentList.getOrNull(position) ?: return
        holder.binding.tvContent.text = item.content
        holder.itemView.setOnClickListener {
            listener?.onClickListener?.invoke(item, position)
        }
        holder.binding.ivDelete.setOnClickListener {
            listener?.onRemoveListener?.invoke(item, position)
        }

        holder.binding.ivEdit.setOnClickListener {
            listener?.onEditListener?.invoke(item, position)
        }
    }

    class ViewHolder(val binding: ItemTemplateListBinding) : RecyclerView.ViewHolder(binding.root)

    data class TextContentTemplateListAdapterListener(
        val onClickListener: OnClickListener? = null,
        val onEditListener: OnEditListener? = null,
        val onRemoveListener: OnRemoveListener? = null
    )
}

typealias OnEditListener = (template: Template, pos: Int) -> Unit
typealias OnClickListener = (template: Template, pos: Int) -> Unit
typealias OnRemoveListener = (template: Template, pos: Int) -> Unit

fun TextContentTemplateListAdapter(lambda: (TextContentTemplateListAdapterListenerBuilder.() -> Unit)) =
    TextContentTemplateListAdapterListenerBuilder().apply(lambda).build()

class TextContentTemplateListAdapterListenerBuilder {
    private var onEditListener: OnEditListener? = null
    private var onClickListener: OnClickListener? = null
    private var onRemoveListener: OnRemoveListener? = null

    fun setOnEditListener(listener: OnEditListener) {
        onEditListener = listener
    }

    fun setOnClickListener(listener: OnClickListener) {
        onClickListener = listener
    }

    fun setOnRemoveListener(listener: OnRemoveListener) {
        onRemoveListener = listener
    }

    fun build(): TextContentTemplateListAdapter {
        return TextContentTemplateListAdapter(
            TextContentTemplateListAdapter.TextContentTemplateListAdapterListener(
                onClickListener, onEditListener, onRemoveListener
            )
        )
    }
}