package me.rosuh.easywatermark.ui.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.FragmentGalleryBinding
import me.rosuh.easywatermark.ui.adapter.GalleryAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.ui.widget.UniformScrollGridLayoutManager
import me.rosuh.easywatermark.utils.FileUtils
import me.rosuh.easywatermark.utils.MultiPickContract


class GalleryFragment : BaseBindBSDFragment<FragmentGalleryBinding>() {

    companion object {
        private const val TAG = "GalleryFragment"
    }

    private var isScrollSliderManually: Boolean = false
    private var refreshRate: Float = 60f
    private val galleryAdapter by lazy { GalleryAdapter() }

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    private var doOnDismiss: () -> Unit = {}

    fun doOnDismiss(doOnDismiss: () -> Unit) {
        this.doOnDismiss = doOnDismiss
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickImageLauncher =
            registerForActivityResult(MultiPickContract()) { uri: List<Uri?>? ->
                handleActivityResult(uri)
            }
        shareViewModel.query(requireContext().contentResolver)

        val displayManager: DisplayManager =
            requireContext().applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        refreshRate = displayManager.displays?.getOrNull(0)?.refreshRate ?: 60F
        Log.i(TAG, "onCreate $refreshRate")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = object : BottomSheetDialog(requireContext()) {
            override fun onBackPressed() {
                if (galleryAdapter.getSelectedList().isEmpty()) {
                    dismiss()
                } else {
                    galleryAdapter.unSelectAll(binding.rvContent)
                }
            }
        }.apply {
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            isCancelable = false
        }
        return d
    }

    override fun onStart() {
        super.onStart()
        val sheetContainer = requireView().parent as? ViewGroup ?: return
        sheetContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentGalleryBinding {
        val rootView = FragmentGalleryBinding.inflate(layoutInflater, container, false)
        rootView.topAppBar.setNavigationOnClickListener {
            dismissAllowingStateLoss()
        }
        rootView.fab.apply {
            setOnClickListener {
                shareViewModel.selectGallery(galleryAdapter.getSelectedList())
                hide()
                dismissAllowingStateLoss()
            }
        }
        rootView.rvContent.apply {
            layoutManager = UniformScrollGridLayoutManager(requireContext(), 4).also {
                it.scrollBarView = rootView.ivSlider
            }
            adapter = galleryAdapter
            setHasFixedSize(true)
            setOnSelect { rv, end ->
                galleryAdapter.select(rv, end)
            }
            setOnUnSelect { rv, end ->
                galleryAdapter.unSelect(rv, end)
            }
            addOnScrollListener(object :RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (isScrollSliderManually) {
                        return
                    }
                    val verticalScrollRange = recyclerView.computeVerticalScrollRange()
                    val computeVerticalScrollOffset = recyclerView.computeVerticalScrollOffset()
                    Log.i(TAG, "onScrolled verticalScrollRange = $verticalScrollRange, computeVerticalScrollOffset = ${recyclerView.computeVerticalScrollOffset()}, computeVerticalScrollExtent = ${recyclerView.computeVerticalScrollExtent()}")
                    rootView.ivSlider.translationY = ((computeVerticalScrollOffset.toFloat() / verticalScrollRange) * (recyclerView.bottom - recyclerView.paddingBottom)).coerceAtLeast(0f)
                }
            })
        }

        rootView.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.iv_sys_image -> {
                    val mime = "image/*"
                    pickImageLauncher.launch(mime)
                    return@setOnMenuItemClickListener true
                }
            }
            return@setOnMenuItemClickListener false
        }

        rootView.ivSlider.apply {
            post {
                translationX += this.measuredWidth / 5 * 1
            }
            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (v == null) {
                        return false
                    }
                    val totalHeight = rootView.rvContent.bottom - rootView.rvContent.paddingBottom
                    val percent = binding.rvContent.computeVerticalScrollRange() / totalHeight
                    Log.d(
                        TAG,
                        "ivSlider totalHeight = $totalHeight, top = ${this@apply.top.toFloat()}, percent = $percent"
                    )
                    when (event?.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.x
                            startY = event.y
                            this@apply.animate().scaleX(2f).scaleY(2f).start()
                            binding.rvContent.stopScroll()
                            isScrollSliderManually = true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - startX
                            val dy = event.y - startY
                            val targetPos = dy * percent
                            Log.i(TAG, "ivSlider targetPos = $targetPos, dy = $dy")
                            v.let {
                                it.translationY = (dy + it.translationY).coerceAtLeast(0f)
                                    .coerceAtMost(totalHeight.toFloat())
                            }
                            binding.rvContent.stopScroll()
                            binding.rvContent.scrollBy(0, targetPos.toInt())
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                            this@apply.animate().scaleX(1f).scaleY(1f).start()
                            isScrollSliderManually = false
                        }
                    }
                    return true
                }
            })
        }

        shareViewModel.galleryPickedImageList.observe(this) {
            galleryAdapter.submitList(it)
        }
        galleryAdapter.selectedCount.observe(this) {
            if (it > 0) {
                rootView.fab.apply {
                    text = it.coerceAtLeast(0).toString()
                    show()
                }
            } else {
                rootView.fab.hide()
            }
        }
        return rootView
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        doOnDismiss.invoke()
        shareViewModel.resetGalleryData()
    }

    private fun handleActivityResult(list: List<Uri?>?) {
        val finalList = list?.filterNotNull()?.filter {
            FileUtils.isImage(requireContext().contentResolver, it)
        } ?: emptyList()
        if (finalList.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.tips_do_not_choose_image),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (FileUtils.isImage(requireContext().contentResolver, finalList.first())) {
            shareViewModel.updateImageList(finalList)
            dismissAllowingStateLoss()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.tips_choose_other_file_type),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}