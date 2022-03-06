package me.rosuh.easywatermark.ui.adapter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.widget.RadioButton
import me.rosuh.easywatermark.utils.ktx.*


class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.GalleryItemHolder>() {

    companion object {
        private const val TAG = "GalleryAdapter"
    }

    private var longPressPos: Int = 0

    var isLongClick: Boolean = false
    private val itemCallback: DiffUtil.ItemCallback<Image> =
        object : DiffUtil.ItemCallback<Image>() {
            override fun areItemsTheSame(
                oldItem: Image,
                newItem: Image
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: Image,
                newItem: Image
            ): Boolean {
                return oldItem.id == newItem.id
            }

        }

    val selectedCount: MutableLiveData<Int> = MutableLiveData(0)

    private val differ: ArrayList<Image> = ArrayList()


    fun submitList(list: List<Image>) {
        differ.addAll(list)
        notifyDataSetChanged()
    }

    private var maxSize = 120.dp

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryItemHolder {
        maxSize = (parent.width / 3 - 16.dp).toInt()
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_image_gallery, parent, false)
        return GalleryItemHolder(view)
    }

    fun getItem(position: Int): Image? {
        return differ.getOrNull(position)
    }

    fun getSelectedList() = differ.filter { it.check }

    override fun onViewDetachedFromWindow(holder: GalleryItemHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.cbImage.setOnCheckedChangeListener { }
    }

    override fun onBindViewHolder(
        holder: GalleryItemHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNullOrEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        payloads.filterIsInstance<String>().forEach {
            when (it) {
                "selectTo" -> {
                    holder.cbImage.isChecked = true
                }
                "unSelect" -> {
                    holder.cbImage.isChecked = false
                }
            }
        }
    }

    override fun onBindViewHolder(holder: GalleryItemHolder, position: Int) {
        if (position < 0 || position >= differ.size || getItem(position) == null) {
            return
        }
        with(getItem(position)!!) {
            holder.itemView.apply {
                setOnLongClickListener {
                    holder.cbImage.toggle()
                    isLongClick = true
                    longPressPos = position
                    return@setOnLongClickListener true
                }
                setOnClickListener {
                    holder.cbImage.toggle()
                }
            }
            holder.cbImage.apply {
                isChecked = this@with.check
                applyCheckStyle(holder.ivImage, this@with.check, false)
                setOnCheckedChangeListener { isChecked ->
                    selectedCount.value =
                        if (isChecked) selectedCount.value!! + 1 else selectedCount.value!! - 1
                    this@with.check = isChecked
                    applyCheckStyle(holder.ivImage, isChecked)
                }
            }
            holder.ivImage.loadSmall(uri)
        }
    }

    private val scaleAnimationIn = ScaleAnimation(
        1f,
        0.7f,
        1f,
        0.7f,
        ScaleAnimation.RELATIVE_TO_SELF,
        0.5f,
        ScaleAnimation.RELATIVE_TO_SELF,
        0.5f
    )

    private val scaleAnimationOut = ScaleAnimation(
        0.7f,
        1f,
        0.7f,
        1f,
        ScaleAnimation.RELATIVE_TO_SELF,
        0.5f,
        ScaleAnimation.RELATIVE_TO_SELF,
        0.5f
    )

    private fun applyCheckStyle(
        imageFilterView: ImageFilterView,
        isChecked: Boolean,
        animate: Boolean = true,
        post: () -> Unit = {}
    ) {
        val d = 120L
        val round = .75f
        val scale = 0.7f
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
        return differ.size
    }

    fun selectTo(end: Int) {
        val start = longPressPos
        Log.i(TAG, "selectTo $start .. $end")
        val list = differ
        if (start < 0 || end >= list.size || start >= end) {
            return
        }
        for (i in start .. end) {
            list[i].check = true
            notifyItemChanged(i, "selectTo")
        }
    }

    fun unSelect(position: Int) {
        Log.i(TAG, "unSelect pos = $position")
        val list = differ
        if (position < 0 || position >= list.size) {
            return
        }
        for (i in list.size - 1 downTo position) {
            list[i].check = false
            notifyItemChanged(i, "unSelect")
        }
    }

    class GalleryItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbImage: RadioButton = view.findViewById(R.id.cb_image)

        val ivImage: ImageFilterView = view.findViewById(R.id.iv_image)

        init {
            view.setBackgroundColor(view.context.colorSurfaceVariant)
        }
    }
}