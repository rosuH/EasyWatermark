package me.rosuh.easywatermark.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.FragmentGalleryBinding
import me.rosuh.easywatermark.ui.adapter.GalleryAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment


class GalleryFragment : BaseBindBSDFragment<FragmentGalleryBinding>() {

    private val galleryAdapter by lazy { GalleryAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        shareViewModel.resetGalleryData()
    }
}