package me.rosuh.easywatermark.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.collection.ArraySet
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.base.BaseViewHolder
import me.rosuh.easywatermark.model.ImageInfo
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.widget.PhotoPreviewItem

class PhotoListPreviewAdapter(
    private val context: Context
) : RecyclerView.Adapter<PhotoListPreviewAdapter.ImageHolder>() {

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

    private val inRemoveModeSet by lazy { ArraySet<ImageInfo>() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
        val rootView = PhotoPreviewItem(context)
        return ImageHolder(rootView)
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
        processUI(holder, position, isPayLoad = true)
    }

    override fun onBindViewHolder(holder: ImageHolder, position: Int) {
        processUI(holder, position)
    }

    private fun processUI(holder: ImageHolder, position: Int, isPayLoad: Boolean = false) {
        if (position < 0 || position >= differ.currentList.size) {
            return
        }
        (holder.itemView as PhotoPreviewItem).apply {
            onRemove {
                VibrateHelper.get().doVibrate(holder.itemView)
                remove(holder.bindingAdapterPosition)
            }
            onRemovePreview {
                VibrateHelper.get().doVibrate(holder.itemView)
            }
        }
        with(differ.currentList[position]) {
            Glide.with(MyApp.instance)
                .load(this.uri)
                .into(holder.ivIcon)
            when {
                isInRemovedMode(position) -> {
                    holder.ivIcon.animate().alpha(0.5f).setDuration(100).start()
                    holder.ivRemove.animate()
                        .alpha(1f)
                        .setDuration(100)
                        .withStartAction { holder.ivRemove.isVisible = true }
                        .start()
                }
                isPayLoad -> {
                    holder.ivIcon.animate().alpha(1f).setDuration(100).start()
                    holder.ivRemove
                        .animate()
                        .alpha(0f)
                        .setDuration(100)
                        .withEndAction { holder.ivRemove.isVisible = false }
                        .start()
                }
                else -> {
                    holder.ivIcon.apply {
                        isVisible = true
                        alpha = 1f
                    }
                    holder.ivRemove.apply {
                        isVisible = false
                        alpha = 0f
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return differ.currentList.count()
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    fun submitList(imageInfoList: List<ImageInfo>) {
        inRemoveModeSet.clear()
        differ.submitList(imageInfoList)
    }

    fun getItem(pos: Int): ImageInfo? {
        return differ.currentList.getOrNull(pos)
    }

    fun toggleRemovedMode(position: Int, view: View) {
        if (position < 0 || position >= differ.currentList.size) {
            return
        }
        if (itemCount <= 1) {
            VibrateHelper.get().doVibrate(view)
            return
        }
        val item = getItem(position)
        if (inRemoveModeSet.contains(item)) {
            inRemoveModeSet.remove(item)
        } else {
            inRemoveModeSet.add(item)
        }
        notifyItemChanged(position, "Removed")
    }

    fun isInRemovedMode(pos: Int): Boolean = inRemoveModeSet.contains(getItem(pos))

    var selectedPos: Int = 0

    private var doOnRemoveItem: (ImageInfo?) -> Unit = {}

    fun onRemove(block: (ImageInfo?) -> Unit = {}) {
        this.doOnRemoveItem = block
    }

    private fun remove(removePos: Int): Boolean {
        if (removePos < 0 || removePos >= itemCount || itemCount <= 1) {
            return false
        }
        inRemoveModeSet.remove(getItem(removePos))
        val list = ArrayList(differ.currentList)
        list.removeAt(removePos)
        selectedPos = if (removePos < selectedPos || removePos >= itemCount - 1) {
            (selectedPos - 1).coerceAtLeast(0)
        } else {
            selectedPos
        }
        differ.submitList(list) {
            Log.i("remove", "$removePos, $itemCount")
            doOnRemoveItem.invoke(getItem(selectedPos))
        }
        return true
    }

    class ImageHolder(view: PhotoPreviewItem) : BaseViewHolder(view) {
        val ivIcon: ImageView = view.ivIcon
        val ivRemove: ImageView = view.ivDel.apply {
            this.isVisible = false
        }
    }
}
