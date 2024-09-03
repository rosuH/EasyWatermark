package me.rosuh.easywatermark.ui.dialog

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.databinding.DialogSaveFileBinding
import me.rosuh.easywatermark.ui.MainActivity
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.ui.adapter.SaveImageListAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.utils.ktx.preCheckStoragePermission


class SaveImageBSDialogFragment : BaseBindBSDFragment<DialogSaveFileBinding>() {
    private val imageList: List<ImageInfo>
        get() = (requireContext() as MainActivity).getImageList()

    private val popArray = arrayOf("JPEG", "PNG")

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): DialogSaveFileBinding {
        val root = DialogSaveFileBinding.inflate(layoutInflater, container, false)
        val isSaving = shareViewModel.saveResult.value?.code == MainViewModel.TYPE_SAVING
        with(root) {
            btnSave.apply {
                setOnClickListener {
                    if (shareViewModel.saveResult.value?.code == MainViewModel.TYPE_JOB_FINISH) {
                        // share to other apps
                        openShare()
                    } else {
                        // saving jobs
                        (activity as? MainActivity)?.requestPermission {
                            shareViewModel.saveImage(
                                requireActivity().contentResolver,
                                (requireContext() as MainActivity).getImageViewInfo(),
                                (requireContext() as MainActivity).getImageList()
                            )
                        }
                    }
                }
            }

            btnOpenGallery.apply {
                this.isInvisible = true
                setOnClickListener {
                    openGallery()
                }
            }

            atvFormat.also {
                it.setAdapter(
                    ArrayAdapter(
                        requireContext(),
                        R.layout.simple_dropdown_item_1line,
                        popArray
                    )
                )
                it.setText(
                    if (shareViewModel.outputFormat == Bitmap.CompressFormat.JPEG) "JPEG" else "PNG",
                    false
                )
                it.setOnItemClickListener { _, _, index, _ ->
                    val targetFormat =
                        if (index == 0) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    shareViewModel.saveOutput(targetFormat, slideQuality.value.toInt())
                    flQuality.isVisible = targetFormat == Bitmap.CompressFormat.JPEG
                    slideQuality.isVisible = targetFormat == Bitmap.CompressFormat.JPEG
                }
            }

            flQuality.isVisible = shareViewModel.outputFormat == Bitmap.CompressFormat.JPEG
            slideQuality.isVisible = shareViewModel.outputFormat == Bitmap.CompressFormat.JPEG

            rvResult.apply {
                adapter = SaveImageListAdapter(requireContext()).also {
                    it.submitList(imageList)
                }
                itemAnimator = null
                val spanCount = when {
                    imageList.size < 5 -> imageList.size.coerceAtLeast(1)
                    imageList.size < 20 && imageList.size % 2 == 0 -> imageList.size / 2
                    imageList.size < 20 -> imageList.size / 2 + 1
                    else -> 10
                }
                scrollBarStyle
                layoutManager = GridLayoutManager(requireContext(), spanCount)
            }
            val compressLevel = shareViewModel.compressLevel.toFloat()

            val theAdapter = rvResult.adapter as SaveImageListAdapter

            tvQualityValue.text = compressLevel.toInt().toString()

            tvResult.text = requireContext().getString(
                R.string.dialog_save_export_list_title,
                "${theAdapter.data.count { it.jobState is JobState.Success }}/${theAdapter.itemCount}"
            )

            slideQuality.apply {
                value = compressLevel
                addOnChangeListener { _, value, _ ->
                    shareViewModel.saveOutput(shareViewModel.outputFormat, value.toInt())
                    tvQualityValue.text = value.toInt().toString()
                }
            }

            shareViewModel.saveProcess.observe(viewLifecycleOwner) {
                theAdapter.updateJobState(it)
                if (it?.jobState is JobState.Success) {
                    val count = theAdapter.finishCount
                    tvResult.text = requireContext().getString(
                        R.string.dialog_save_export_list_title,
                        "$count/${theAdapter.itemCount}"
                    )
                }
            }

            shareViewModel.saveResult.observe(viewLifecycleOwner) {
                setUpLoadingView(it)
            }

            shareViewModel.colorPalette.observe(viewLifecycleOwner) {

            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpLoadingView(shareViewModel.saveResult.value)
    }

    private fun setUpLoadingView(
        saveResult: Result<*>?
    ) {
        when (saveResult?.code) {
            MainViewModel.TYPE_SAVING -> {
                binding.btnSave.apply {
                    isEnabled = false
                    text = getString(R.string.dialog_save_exporting)
                }
                binding.btnOpenGallery.isInvisible = true
                binding.atvFormat.isEnabled = false
                binding.slideQuality.isEnabled = false
                binding.menuFormat.isEnabled = false
                (dialog as BottomSheetDialog).behavior.isDraggable = false
                isCancelable = false
            }
            MainViewModel.TYPE_JOB_FINISH -> {
                binding.btnSave.apply {
                    isEnabled = true
                    text = getString(R.string.share)
                }
                TransitionManager.beginDelayedTransition(binding.root, AutoTransition())
                binding.btnOpenGallery.isInvisible = false
                binding.atvFormat.isEnabled = true
                binding.slideQuality.isEnabled = true
                binding.menuFormat.isEnabled = true
                (dialog as BottomSheetDialog).behavior.apply {
                    isDraggable = true
                    state = STATE_EXPANDED
                }
                isCancelable = true
            }
            else -> {
                binding.btnSave.apply {
                    isEnabled = true
                    text = getString(R.string.dialog_export_to_gallery)
                }
                binding.btnOpenGallery.isInvisible = true
                binding.atvFormat.isEnabled = true
                binding.slideQuality.isEnabled = true
                binding.menuFormat.isEnabled = true
                (dialog as BottomSheetDialog).behavior.isDraggable = true
                isCancelable = true
                val theAdapter = binding.rvResult.adapter as SaveImageListAdapter
                binding.tvResult.text = requireContext().getString(
                    R.string.dialog_save_export_list_title,
                    "${theAdapter.data.count { it.jobState is JobState.Success }}/${theAdapter.itemCount}"
                )
            }
        }
    }

    private fun openGallery() {
        val list = shareViewModel.imageList.value?.first
        if (list.isNullOrEmpty()) return
        val outputUri = list.first().shareUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(outputUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openShare() {
        val list = ArrayList(shareViewModel.imageList.value?.first ?: emptyList())
        if (list.isEmpty()) return
        val intent = Intent().apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (list.size == 1) {
            val outputUri = list.first().shareUri
            intent.apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, outputUri)
            }
        } else {
            val uriList = ArrayList(list.map { it.shareUri })
            intent.apply {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            }
        }
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Share error with ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    companion object {

        private const val TAG = "SaveImageBSDialogFragment"

        private fun newInstance(): SaveImageBSDialogFragment {
            return SaveImageBSDialogFragment()
        }

        fun safetyHide(manager: FragmentManager) {
            kotlin.runCatching {
                (manager.findFragmentByTag(TAG) as? SaveImageBSDialogFragment)?.dismissAllowingStateLoss()
            }
        }

        fun safetyShow(manager: FragmentManager) {
            try {
                val f = manager.findFragmentByTag(TAG) as? SaveImageBSDialogFragment
                when {
                    f == null -> {
                        newInstance().show(manager, TAG)
                    }
                    !f.isAdded -> {
                        f.show(manager, TAG)
                    }
                }
            } catch (ie: IllegalStateException) {
                ie.printStackTrace()
            }
        }
    }
}
