package me.rosuh.easywatermark.ui.adapter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.util.Log
import android.view.ViewGroup
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.widget.AsyncSquareFrameLayout
import me.rosuh.easywatermark.ui.widget.RadioButton
import me.rosuh.easywatermark.utils.ktx.loadSmall


class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.GalleryItemHolder>() {

    companion object {
        private const val TAG = "GalleryAdapter"
    }

    private var latestSelectedItem: Int = -1

    private val selectedPosSet: MutableSet<Int> = HashSet<Int>()

    val selectedCount: MutableLiveData<Int> = MutableLiveData(0)

    private val differ by lazy {
        AsyncListDiffer<Image>(this, DIFF_CALLBACK)
    }

    private val DIFF_CALLBACK:DiffUtil.ItemCallback<Image> = object : DiffUtil.ItemCallback<Image>() {
        override fun areItemsTheSame(oldItem: Image, newItem: Image): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: Image, newItem: Image): Boolean {
            return oldItem == newItem
        }
    }

    fun submitList(list: List<Image>) {
        differ.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryItemHolder {
        val asyncSquareFrameLayout = AsyncSquareFrameLayout(parent.context)
        return GalleryItemHolder(asyncSquareFrameLayout)
    }

    fun getItem(position: Int): Image? {
        return differ.currentList.getOrNull(position)
    }

    fun getSelectedList() = differ.currentList.filter { it.check }

    override fun onBindViewHolder(
        holder: GalleryItemHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        payloads.filterIsInstance<String>().forEach {
            when (it) {
                "selectTo" -> {
                    holder.bindWhenInflated {
                        holder.cbImage.isChecked = true
                        applyCheckStyle(holder.ivImage, isChecked = true, animate = true)
                    }
                }
                "unSelect" -> {
                    holder.bindWhenInflated {
                        holder.cbImage.isChecked = false
                        applyCheckStyle(holder.ivImage, isChecked = false, animate = true)
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: GalleryItemHolder, position: Int) {
        if (position < 0 || position >= differ.currentList.size || getItem(position) == null) {
            return
        }
        holder.bindWhenInflated {
            val pos = holder.absoluteAdapterPosition
            val item = getItem(pos) ?: return@bindWhenInflated
            with(item) {
                holder.itemView.apply {
                    setOnLongClickListener {
                        holder.cbImage.toggle()
                        latestSelectedItem = holder.absoluteAdapterPosition
                        return@setOnLongClickListener true
                    }
                    setOnClickListener {
                        holder.cbImage.toggle()
                    }
                }
                holder.cbImage.apply {
                    setOnCheckedChangeListener {}
                    isChecked = this@with.check
                    applyCheckStyle(holder.ivImage, this@with.check, false)
                    setOnCheckedChangeListener { isChecked ->
                        applyCheckStyle(holder.ivImage, isChecked)
                        val data =
                            getItem(holder.absoluteAdapterPosition)
                                ?: return@setOnCheckedChangeListener
                        if (data.check && isChecked) {
                            return@setOnCheckedChangeListener
                        }
                        if (!data.check && !isChecked) {
                            return@setOnCheckedChangeListener
                        }
                        selectedCount.value =
                            if (isChecked) selectedCount.value!! + 1 else selectedCount.value!! - 1
                        if (isChecked) {
                            selectedPosSet.add(holder.absoluteAdapterPosition)
                        } else {
                            selectedPosSet.remove(holder.absoluteAdapterPosition)
                        }
                        data.check = isChecked
                    }
                }
                holder.ivImage.post {
                    holder.ivImage.loadSmall(uri, R.drawable.ic_gallery_item_placeholder_container)
                }
            }
        }

    }

    private fun applyCheckStyle(
        imageFilterView: ImageFilterView,
        isChecked: Boolean,
        animate: Boolean = true,
        post: () -> Unit = {}
    ) {
        val d = 200L
        val round = .4f
        val scale = 0.8f
        when {
            isChecked && imageFilterView.scaleX == scale -> return
            !isChecked && imageFilterView.scaleX == 1f -> return
            animate -> {
                val xAnimator =
                    ObjectAnimator.ofFloat(imageFilterView, "scaleX", if (isChecked) scale else 1f)
                val yAnimator =
                    ObjectAnimator.ofFloat(imageFilterView, "scaleY", if (isChecked) scale else 1f)
                val roundAnimator = ObjectAnimator.ofFloat(
                    imageFilterView,
                    "roundPercent",
                    if (isChecked) round else 0f
                )
                AnimatorSet().apply {
                    duration = d
                    interpolator = FastOutSlowInInterpolator()
                    doOnEnd {
                        post.invoke()
                    }
                    playTogether(xAnimator, yAnimator, roundAnimator)
                }.start()
            }
            else -> {
                imageFilterView.scaleX = if (isChecked) scale else 1f
                imageFilterView.scaleY = if (isChecked) scale else 1f
                imageFilterView.roundPercent = if (isChecked) round else 0f
                post.invoke()
            }
        }
    }

    override fun getItemCount(): Int {
        return differ.currentList.size
    }

    fun select(recyclerView: RecyclerView, endPos: Int) {
        val start = latestSelectedItem.coerceAtLeast(0)
        val end = endPos.coerceAtMost(itemCount - 1)
        Log.i(TAG, "selectTo $start .. $end")
        val list = differ.currentList
        if (start < 0 || end >= list.size || start >= end) {
            return
        }
        var changed = false
        for (i in end downTo start) {
            val item = list[i]
            changed = item.check != true
            list[i].check = true
            if (changed) {
                selectedCount.value = selectedCount.value!! + 1
                selectedPosSet.add(i)
            }
            (recyclerView.findViewHolderForAdapterPosition(i) as? GalleryItemHolder?)?.apply {
                cbImage.isChecked = true
                applyCheckStyle(ivImage, true)
            }
        }
        latestSelectedItem = end
    }

    fun unSelect(recyclerView: RecyclerView, position: Int) {
        Log.i(TAG, "unSelect pos = $position")
        val list = differ.currentList
        if (position < 0 || position >= list.size) {
            return
        }
        for (i in list.size - 1 downTo position) {
            val item = list[i]
            if (item.check) {
                list[i].check = false
                selectedCount.value = selectedCount.value!! - 1
                selectedPosSet.remove(i)
                (recyclerView.findViewHolderForAdapterPosition(i) as? GalleryItemHolder?)?.apply {
                    cbImage.isChecked = false
                    applyCheckStyle(ivImage, false)
                }
            }
        }
        latestSelectedItem = position
    }

    fun unSelectAll(recyclerView: RecyclerView) {
        val list = differ.currentList
        selectedPosSet.forEach { i ->
            list[i].check = false
            (recyclerView.findViewHolderForAdapterPosition(i) as? GalleryItemHolder?)?.apply {
                cbImage.isChecked = false
                applyCheckStyle(ivImage, false)
            }
        }
        selectedCount.value = 0
        latestSelectedItem = -1
    }

    fun markAutoScroll(autoScrolling: Boolean, autoSelect: Boolean) {
        Log.i(TAG, "markAutoScroll $autoScrolling, $autoSelect")
    }

    class GalleryItemHolder(view: AsyncSquareFrameLayout) : RecyclerView.ViewHolder(view) {
        lateinit var cbImage: RadioButton

        lateinit var ivImage: ImageFilterView

        private var isInflated = false

        init {
            view.inflate {
                cbImage = view.findViewById(R.id.cb_image)
                ivImage = view.findViewById(R.id.iv_image)
                isInflated = true
                action?.invoke()
            }
        }

        private var action: (() -> Unit)? = null

        fun bindWhenInflated(action: () -> Unit) {
            if (isInflated) {
                action.invoke()
            } else {
                this.action = action
            }
        }
    }
}