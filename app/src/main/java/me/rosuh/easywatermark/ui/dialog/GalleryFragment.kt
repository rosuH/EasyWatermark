package me.rosuh.easywatermark.ui.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.FragmentGalleryBinding
import me.rosuh.easywatermark.ui.adapter.GalleryAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.utils.FileUtils
import me.rosuh.easywatermark.utils.MultiPickContract
import kotlin.math.abs


class GalleryFragment : BaseBindBSDFragment<FragmentGalleryBinding>() {

    companion object {
        private const val TAG = "GalleryFragment"
    }

    private val galleryAdapter by lazy { GalleryAdapter() }

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickImageLauncher =
            registerForActivityResult(MultiPickContract()) { uri: List<Uri?>? ->
                handleActivityResult(uri)
            }
        shareViewModel.query(requireContext().contentResolver)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
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
            adapter = galleryAdapter
            layoutManager = GridLayoutManager(requireContext(), 4)
            setHasFixedSize(true)
            addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                private var isLongPress = false
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (galleryAdapter.isLongClick && !isLongPress) {
                        isLongPress = true
                        galleryAdapter.isLongClick = false
                    }
                    return isLongPress
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    when(e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            Log.i(TAG, "ACTION_DOWN")
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                            Log.i(TAG, "ACTION_CANCEL or ACTION_UP")
                            isLongPress = false
                        }
                    }
                    if (isLongPress) {
                        rvGestureDetector.onTouchEvent(e)
                    }
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

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

        shareViewModel.galleryPickedImageList.observe(this) {
            galleryAdapter.submitList(it)
        }
        galleryAdapter.selectedCount.observe(this) {
            if (it > 0) {
                rootView.fab.apply {
                    text = requireContext().getString(R.string.gallery_pick_size, it)
                    show()
                }
            } else {
                rootView.fab.hide()
            }
        }
        return rootView
    }

    private val rvGestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private var preTouchPos: Int = -1
            private var preX = 0f
            private var preY = 0f

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent?,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val x1 = e1?.x ?: 0f
                val y1 = e1?.y ?: 0f
                val x2 = e2?.x ?: 0f
                val y2 = e2?.y ?: 0f
                val item1 = binding.rvContent.findChildViewUnder(x1, y1)
                val item2 = binding.rvContent.findChildViewUnder(x2, y2)
                val dx = abs(x2 - preX)
                val dy = abs(y2 - preY)
                item2?.let {
                    val pEnd = (binding.rvContent.layoutManager as GridLayoutManager).getPosition(item2)
                    val reduce = (distanceX > 0 || distanceY > 0) && (preTouchPos != pEnd || dx >= it.measuredWidth || dy >= it.measuredHeight)
                    val increase = (distanceX < 0 || distanceY < 0) && (preTouchPos != pEnd || dx >= it.measuredWidth || dy >= it.measuredHeight)
                    Log.i(
                        TAG,
                        "rvGestureDetector onScroll reduce = $reduce, increase = $increase, distanceX = $distanceX, distanceY = $distanceY, $preTouchPos, $pEnd, dx = $dx, dy = $dy"
                    )
                    preTouchPos = pEnd
                    when {
                        increase -> {
                            galleryAdapter.selectTo(pEnd)
                            preX = x2
                            preY = y2
                        }
                        reduce -> {
                            galleryAdapter.unSelect(pEnd)
                            preX = x2
                            preY = y2
                        }
                        else -> {

                        }
                    }
                }
                return super.onScroll(e1, e2, distanceX, distanceY)
            }
        })
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
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