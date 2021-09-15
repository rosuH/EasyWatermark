package me.rosuh.easywatermark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.base.BaseViewHolder
import me.rosuh.easywatermark.model.ImageInfo

class PhotoListPreviewAdapter : RecyclerView.Adapter<PhotoListPreviewAdapter.ImageHolder>() {

    private val differ: AsyncListDiffer<ImageInfo> by lazy {
        AsyncListDiffer(this, differCallback)
    }

    private val differCallback: DiffUtil.ItemCallback<ImageInfo> by lazy {
        object : DiffUtil.ItemCallback<ImageInfo>() {
            override fun areItemsTheSame(oldItem: ImageInfo, newItem: ImageInfo): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: ImageInfo, newItem: ImageInfo): Boolean {
                return oldItem == newItem
            }
        }
    }

    var selectedPos = 0
        set(value) {
            notifyItemChanged(field, "Selected")
            field = value
            notifyItemChanged(value, "Selected")
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_photo_list, parent, false)
        return ImageHolder(view)
    }

    override fun onBindViewHolder(
        holder: ImageHolder,
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

    override fun onBindViewHolder(holder: ImageHolder, position: Int) {
        processUI(holder, position)
    }

    private fun processUI(holder: ImageHolder, position: Int) {
        if (position < 0 || position >= differ.currentList.size) {
            return
        }
        with(differ.currentList[position]) {
            Glide.with(MyApp.instance)
                .load(this.uri)
                .into(holder.ivIcon)
            holder.ivIcon.setBackgroundResource(if (selectedPos == position) R.drawable.bg_selected_photo_list else 0)
        }
    }

    override fun getItemCount(): Int {
        return differ.currentList.count()
    }

    fun submitList(imageInfoList: List<ImageInfo>, toPos: Int = selectedPos) {
        selectedPos = toPos
        differ.submitList(imageInfoList)
    }

    fun getItem(pos: Int): ImageInfo {
        return differ.currentList[pos]
    }

    class ImageHolder(view: View) : BaseViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
    }
}
