package me.rosuh.easywatermark.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.ui.base.BaseViewHolder
import me.rosuh.easywatermark.ui.widget.ProgressImageVIew
import me.rosuh.easywatermark.utils.ktx.appear
import me.rosuh.easywatermark.utils.ktx.disappear

class SaveImageListAdapter(
    private val context: Context
) : RecyclerView.Adapter<SaveImageListAdapter.ImageHolder>() {

    val data: List<ImageInfo>
        get() = differ.currentList

    private var maxLineHeight = 0

    private val differ: AsyncListDiffer<ImageInfo> by lazy {
        AsyncListDiffer(this, differCallback)
    }

    private val differCallback: DiffUtil.ItemCallback<ImageInfo> by lazy {
        object : DiffUtil.ItemCallback<ImageInfo>() {
            override fun areItemsTheSame(oldItem: ImageInfo, newItem: ImageInfo): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: ImageInfo, newItem: ImageInfo): Boolean {
                return oldItem.jobState == newItem.jobState
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
        val rootView =
            LayoutInflater.from(context).inflate(R.layout.item_saving_image, parent, false)

        val holder = ImageHolder(rootView)
        (rootView as ConstraintLayout).apply {
            val h = (parent.height - parent.paddingTop - parent.paddingBottom)
            maxLineHeight = if (itemCount >= 5) h / 2 else h
            maxHeight = maxLineHeight
        }
        (holder.ivIcon).apply {
            updateLayoutParams {
                height = maxLineHeight
            }
        }
        return holder
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
        with(differ.currentList[position]) {
            Glide.with(MyApp.instance)
                .load(this.uri)
                .into(holder.ivIcon)
            when (this.jobState) {
                JobState.Ready -> {
                    holder.ready()
                }
                JobState.Ing -> {
                    holder.start()
                }
                is JobState.Failure -> {
                    holder.failed()
                }
                is JobState.Success -> {
                    holder.success(isPayLoad)
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
        differ.submitList(imageInfoList)
    }

    fun getItem(pos: Int): ImageInfo? {
        return differ.currentList.getOrNull(pos)
    }

    fun updateJobState(it: ImageInfo?) {
        val index = data.indexOf(it).takeIf { it != -1 } ?: return
        Log.i("onBindViewHolder", "payloads, in $index")
        notifyItemChanged(index, "state")
    }

    class ImageHolder(itemView: View) : BaseViewHolder(itemView) {
        fun ready() {
            ivIcon.ready()
            ivDone.isVisible = false
        }

        fun start() {
            ivIcon.start()
        }

        fun success(animate: Boolean = true) {
            ivIcon.finish(animate)
            ivDone.appear()
        }

        fun failed() {
            ivIcon.failed()
            ivDone.disappear()
        }

        val ivIcon: ProgressImageVIew = itemView.findViewById(R.id.iv_icon)
        private val ivDone: ImageView = itemView.findViewById(R.id.iv_done)
    }
}
